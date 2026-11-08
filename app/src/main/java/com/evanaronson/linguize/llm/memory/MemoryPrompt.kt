package com.evanaronson.linguize.llm.memory

import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.FactUpdate
import com.evanaronson.linguize.core.memory.MemoryChanges
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.core.memory.NewFact
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lab: the user-memory contract, with its own instructions and schema (assets [PROMPT] and
 * [SCHEMA]). The model sees facts by short references ("f1", "f2"), not the store's ids;
 * [read] maps them back through the same list of facts.
 */
class MemoryPrompt(val system: String, schemaJson: String) {
    val schema: JsonObject = Json.parseToJsonElement(schemaJson).jsonObject

    /** The request to learn from [messages], with [facts] as they are, notes in [native]. */
    fun request(facts: List<Fact>, messages: List<MemoryMessage>, native: String) = GenerationRequest(
        system = system,
        user = userMessage(facts, messages, native),
        schema = schema,
        name = "memory",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /** Facts and the exchange as untrusted data. Only the most recent messages that fit are sent. */
    fun userMessage(facts: List<Fact>, messages: List<MemoryMessage>, native: String): String = buildJsonObject {
        put("writeIn", native)
        putJsonArray("facts") {
            facts.forEachIndexed { index, fact ->
                addJsonObject {
                    put("id", reference(index))
                    put("text", fact.text)
                    put("category", fact.category.token)
                    put("yours", fact.byWriter)
                }
            }
        }
        put("room", (MemoryState.MAX_FACTS - facts.size).coerceAtLeast(0))
        putJsonArray("conversation") {
            recent(messages).forEach { message ->
                addJsonObject {
                    put("from", if (message.fromWriter) "writer" else "partner")
                    put("text", message.text)
                }
            }
        }
    }.toString()

    /**
     * The model's answer as changes to [facts] (the same list the request was built from).
     * Items that can't be used (unknown references, unknown categories, empty text) are
     * skipped; an answer that isn't the expected object throws [IllegalArgumentException].
     */
    fun read(raw: String, facts: List<Fact>): MemoryChanges {
        require(raw.isNotBlank() && raw.length <= MAX_REPLY) { "Unusable memory reply" }
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("Not an object")
        val ids = facts.mapIndexed { index, fact -> reference(index) to fact.id }.toMap()
        val add = root.items("add").mapNotNull { item ->
            val text = item.string("text") ?: return@mapNotNull null
            val category = FactCategory.read(item.string("category")) ?: return@mapNotNull null
            NewFact(text, category)
        }
        val update = root.items("update").mapNotNull { item ->
            val id = ids[item.string("id")?.trim()] ?: return@mapNotNull null
            val text = item.string("text") ?: return@mapNotNull null
            FactUpdate(id, text, FactCategory.read(item.string("category")))
        }
        val remove = (root["remove"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.let(ids::get) }
        return MemoryChanges(add.take(MAX_ITEMS), update.take(MAX_ITEMS), remove.take(MAX_ITEMS))
    }

    private fun JsonObject.items(name: String): List<JsonObject> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val PROMPT = "lab_memory_prompt.md"
        const val SCHEMA = "lab_memory_schema.json"
        const val MAX_OUTPUT_TOKENS = 1024
        private const val MAX_REPLY = 16_384
        private const val MAX_ITEMS = 6

        /** About 3,000 words of conversation: the end of a long chat matters most. */
        const val MAX_CONVERSATION_CHARS = 16_000
        private const val MAX_MESSAGE_CHARS = 2_000

        fun load(read: (String) -> String) = MemoryPrompt(read(PROMPT), read(SCHEMA))

        private fun reference(index: Int) = "f${index + 1}"

        /** The latest messages within [MAX_CONVERSATION_CHARS], each cut to [MAX_MESSAGE_CHARS], in order. */
        internal fun recent(messages: List<MemoryMessage>): List<MemoryMessage> {
            val kept = ArrayDeque<MemoryMessage>()
            var used = 0
            for (message in messages.asReversed()) {
                val text = message.text.trim().take(MAX_MESSAGE_CHARS)
                if (text.isEmpty()) continue
                if (used + text.length > MAX_CONVERSATION_CHARS) break
                used += text.length
                kept.addFirst(message.copy(text = text))
            }
            return kept.toList()
        }
    }
}
