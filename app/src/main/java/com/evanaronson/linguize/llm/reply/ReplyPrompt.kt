package com.evanaronson.linguize.llm.reply

import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntent
import com.evanaronson.linguize.core.reply.ReplyIntents
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What a reply is drafted from: the incoming message, how it was read, and who is writing back. */
data class ReplyInput(
    val message: String,
    /** The translation the writer read; empty when there was none. */
    val translation: String,
    val native: String,
    val writingProfiles: List<WritingProfile>,
)

/**
 * Lab: drafting a reply to an incoming message. Its own instructions and schemas, read from
 * `lab_reply-drafter_prompt.md` and `lab_reply-drafter_schema.json`; it never sends or reads
 * the check contract.
 */
class ReplyPrompt(val system: String, schemaJson: String) {
    private val schemas: JsonObject = json.parseToJsonElement(schemaJson).jsonObject
    private val intentsSchema = requireNotNull(schemas["intents"]).jsonObject
    private val replySchema = requireNotNull(schemas["reply"]).jsonObject

    /** Two or three ways to answer [input]'s message. */
    fun intentsRequest(input: ReplyInput) = GenerationRequest(
        system = system,
        user = userMessage(TASK_INTENTS, input, intent = null),
        schema = intentsSchema,
        name = "reply_intents",
        maxTokens = 1024,
    )

    /**
     * A reply to [input]'s message that says what the writer described in [intent]. With
     * [simplerThan], the writer found that earlier draft too hard.
     */
    fun replyRequest(input: ReplyInput, intent: String, simplerThan: String? = null) = GenerationRequest(
        system = system,
        user = userMessage(TASK_REPLY, input, intent, simplerThan),
        schema = replySchema,
        name = "reply_draft",
        maxTokens = 2048,
    )

    /** One JSON object, so neither the message nor the intent can forge another field. */
    internal fun userMessage(task: String, input: ReplyInput, intent: String?, simplerThan: String? = null): String = buildJsonObject {
        put("task", task)
        put("message", input.message)
        put("translation", input.translation)
        put("native", input.native)
        put("writingProfiles", json.encodeToJsonElement(input.writingProfiles))
        if (intent != null) put("intent", intent)
        if (simplerThan != null) put("simplerThan", simplerThan)
    }.toString()

    /** Blank or oversized proposals are dropped; an answer with none left is unusable. */
    fun readIntents(raw: String): ReplyIntents = reading(raw) {
        val answer = json.decodeFromString<ReplyIntents>(raw)
        val intents = answer.intents
            .map { ReplyIntent(it.label.trim(), it.instruction.trim()) }
            .filter { it.label.isNotEmpty() && it.label.length <= MAX_LABEL && it.instruction.isNotEmpty() && it.instruction.length <= MAX_INSTRUCTION }
            .distinctBy { it.label.lowercase() }
            .take(MAX_INTENTS)
        if (intents.isEmpty() || answer.language.length > MAX_LANGUAGE) invalid()
        ReplyIntents(answer.language.trim(), intents)
    }

    /**
     * Every sentence needs its text and its gloss: a reply the writer can't read back isn't
     * offered. Words to learn are optional, so one not found in the reply is dropped instead.
     */
    fun readReply(raw: String): ReplyDraft = reading(raw) {
        val answer = json.decodeFromString<ReplyDraft>(raw)
        if (answer.language.isBlank() || answer.language.length > MAX_LANGUAGE ||
            answer.sentences.isEmpty() || answer.sentences.size > MAX_SENTENCES ||
            answer.sentences.any { it.text.isBlank() || it.text.length > MAX_SENTENCE || it.gloss.isBlank() || it.gloss.length > MAX_GLOSS }) {
            invalid()
        }
        val sentences = answer.sentences.mapIndexed { index, sentence ->
            sentence.copy(
                text = sentence.text.trim(),
                gloss = sentence.gloss.trim(),
                lineBreak = sentence.lineBreak && index < answer.sentences.lastIndex,
            )
        }
        val draft = ReplyDraft(answer.language.trim(), sentences, emptyList())
        if (draft.text.length > MAX_REPLY) invalid()
        val reply = draft.text
        val words = answer.words
            .map { it.copy(word = it.word.trim(), meaning = it.meaning.trim()) }
            .filter { it.word.isNotEmpty() && it.meaning.isNotEmpty() && it.meaning.length <= MAX_MEANING && reply.contains(it.word) }
            .distinctBy { it.word.lowercase() }
            .take(MAX_WORDS)
        draft.copy(words = words)
    }

    private inline fun <T> reading(raw: String, read: () -> T): T {
        if (raw.isBlank()) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.EmptyReply)
        if (raw.length > MAX_ANSWER) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.ReplyTooLarge)
        return try {
            read()
        } catch (e: SerializationException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        } catch (e: IllegalArgumentException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        }
    }

    private fun invalid(): Nothing =
        throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)

    companion object {
        const val PROMPT_ASSET = "lab_reply-drafter_prompt.md"
        const val SCHEMA_ASSET = "lab_reply-drafter_schema.json"
        const val TASK_INTENTS = "intents"
        const val TASK_REPLY = "reply"

        /** The longest intent the writer can type. */
        const val MAX_INTENT_INPUT = 1_000

        private const val MAX_ANSWER = 32_768
        private const val MAX_LANGUAGE = 64
        private const val MAX_INTENTS = 3
        private const val MAX_LABEL = 60
        private const val MAX_INSTRUCTION = 300
        private const val MAX_SENTENCES = 12
        private const val MAX_SENTENCE = 1_000
        private const val MAX_GLOSS = 2_000
        private const val MAX_REPLY = 4_000
        private const val MAX_WORDS = 4
        private const val MAX_MEANING = 200

        private val json = Json { ignoreUnknownKeys = false }

        fun load(read: (String) -> String) = ReplyPrompt(read(PROMPT_ASSET), read(SCHEMA_ASSET))
    }
}
