package com.evanaronson.linguize.llm.chat

import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One turn of the conversation as the partner sees it: what the writer sent, or what it said. */
data class Turn(val fromWriter: Boolean, val text: String)

/**
 * What the partner answers with: its message, the same message in the writer's own language
 * (null when the answer had none), and a title when one was asked for.
 */
data class PartnerReply(val reply: String, val title: String?, val translation: String? = null)

/** Who the partner is talking to and in what language. */
data class ChatSetting(
    /** The English name of the conversation's language ("Spanish"). */
    val language: String,
    /** Its regional variety, or "". */
    val variety: String,
    /** The writer's own language, for titles. */
    val native: String,
    /** [com.evanaronson.linguize.core.memory.UserMemory.forPrompt], or "". */
    val memory: String,
    /** The practice targets block ([com.evanaronson.linguize.core.chat.PracticeTargets.render]), or "". */
    val targets: String = "",
    /** Who the partner plays, for a stand-in conversation; null for the usual partner. */
    val persona: String? = null,
)

/**
 * Lab Chat: the conversation partner's request. Its own instructions and schema, read from
 * `lab_chat_prompt.md` and `lab_chat_schema.json`; it never sends the check contract. The
 * partner never comments on grammar: checking happens before the writer sends.
 */
class ChatPrompt(private val instructions: String, schemaJson: String) {
    private val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    /** The instructions, with what memory holds about the writer and what to steer toward at the end. */
    fun system(memory: String, targets: String = ""): String = buildString {
        append(instructions)
        val notes = memory.trim().take(MAX_MEMORY)
        if (notes.isNotEmpty()) append("\n\n## What you remember about the learner\n\n").append(notes).append('\n')
        val steer = targets.trim().take(MAX_TARGETS)
        if (steer.isNotEmpty()) append("\n\n## Practice targets\n\n").append(steer).append('\n')
    }

    /** A new conversation's opener. */
    fun openRequest(setting: ChatSetting) = request(TASK_OPEN, setting, emptyList(), needTitle = false)

    /** A reply to the last turn of [history]; asks for a title with the first exchange. */
    fun replyRequest(setting: ChatSetting, history: List<Turn>, needTitle: Boolean) =
        request(TASK_REPLY, setting, trim(history), needTitle)

    private fun request(task: String, setting: ChatSetting, history: List<Turn>, needTitle: Boolean) = GenerationRequest(
        system = system(setting.memory, setting.targets),
        user = userMessage(task, setting, history, needTitle),
        schema = schema,
        name = "chat_reply",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /** One JSON object, so nothing the writer typed can forge another field. */
    internal fun userMessage(task: String, setting: ChatSetting, history: List<Turn>, needTitle: Boolean): String = buildJsonObject {
        put("task", task)
        put("language", setting.language)
        put("variety", setting.variety)
        put("native", setting.native)
        put("persona", setting.persona?.trim()?.take(MAX_PERSONA).orEmpty())
        putJsonArray("history") {
            history.forEach { turn ->
                add(buildJsonObject {
                    put("from", if (turn.fromWriter) "learner" else "you")
                    put("text", turn.text)
                })
            }
        }
        put("needTitle", needTitle)
    }.toString()

    /** Reads the partner's answer. A blank or oversized reply is unusable; a bad title is dropped. */
    fun read(raw: String): PartnerReply {
        if (raw.isBlank()) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.EmptyReply)
        if (raw.length > MAX_ANSWER) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.ReplyTooLarge)
        val fields = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: SerializationException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        } catch (e: IllegalArgumentException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        }
        val reply = runCatching { fields["reply"]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
        if (reply.isEmpty() || reply.length > MAX_REPLY) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        }
        val title = runCatching { fields["title"]?.jsonPrimitive?.content }.getOrNull()?.trim()
            ?.trimEnd('.')?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE && '\n' !in it }
        // A missing or unusable translation never costs the reply: it can be asked for later.
        val translation = runCatching { fields["translation"]?.jsonPrimitive?.content }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TRANSLATION && it != reply }
        return PartnerReply(reply, title, translation)
    }

    companion object {
        const val PROMPT_ASSET = "lab_chat_prompt.md"
        const val SCHEMA_ASSET = "lab_chat_schema.json"
        const val TASK_OPEN = "open"
        const val TASK_REPLY = "reply"

        /** How much conversation goes with each request: the newest turns that fit. */
        const val MAX_HISTORY_CHARS = 12_000
        const val MAX_TURNS = 40
        private const val MAX_MEMORY = 4_000
        private const val MAX_TARGETS = 1_500
        const val MAX_PERSONA = 300
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_ANSWER = 16_384
        private const val MAX_REPLY = 2_000
        private const val MAX_TITLE = 60
        private const val MAX_TRANSLATION = 3_000

        private val json = Json { ignoreUnknownKeys = true }

        fun load(read: (String) -> String) = ChatPrompt(read(PROMPT_ASSET), read(SCHEMA_ASSET))

        /**
         * The newest turns of [history] within [maxTurns] and [maxChars], oldest first. The
         * latest turn always goes, cut to its last [maxChars] if it alone is longer.
         */
        fun trim(history: List<Turn>, maxChars: Int = MAX_HISTORY_CHARS, maxTurns: Int = MAX_TURNS): List<Turn> {
            if (history.isEmpty()) return history
            val kept = ArrayDeque<Turn>()
            var used = 0
            for (turn in history.asReversed()) {
                if (kept.size >= maxTurns) break
                if (kept.isEmpty()) {
                    val text = turn.text.takeLast(maxChars)
                    kept.addFirst(turn.copy(text = text))
                    used += text.length
                    continue
                }
                if (used + turn.text.length > maxChars) break
                kept.addFirst(turn)
                used += turn.text.length
            }
            return kept.toList()
        }
    }
}
