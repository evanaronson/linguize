package com.evanaronson.linguize.llm.teachme

import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The "Teach me" contract: its own instructions and schema (assets [PROMPT] and [SCHEMA]),
 * separate from the check's, so a lesson never touches the correction engine.
 */
class LessonPrompt(val system: String, schemaJson: String) {
    val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    /** The request for [topic], explained in [native], the writer's own language. */
    fun request(topic: LessonTopic, native: String) = GenerationRequest(
        system = system,
        user = userMessage(topic, native),
        schema = schema,
        name = "lesson",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /** The topic as untrusted data; the writer's language is set by the app, never by the text. */
    fun userMessage(topic: LessonTopic, native: String): String = buildJsonObject {
        put("explainIn", native)
        put("language", topic.language.orEmpty().trim())
        put("from", topic.from.trim())
        put("to", topic.to.trim())
        put("why", topic.why.trim())
        put("sentence", topic.sentence.orEmpty().trim().take(MAX_SENTENCE))
    }.toString()

    /** The model's answer as a lesson, or a [CheckFailure] when it isn't one screen's worth of usable lesson. */
    fun read(raw: String): Lesson = try {
        if (raw.isBlank()) invalid(CheckFailure.Message.EmptyReply)
        if (raw.length > MAX_REPLY) invalid(CheckFailure.Message.ReplyTooLarge)
        validated(json.decodeFromString<Lesson>(raw))
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    }

    private fun validated(lesson: Lesson): Lesson {
        val trimmed = lesson.copy(
            language = lesson.language.trim(),
            rule = lesson.rule.trim(),
            yours = lesson.yours.trim(),
            trap = lesson.trap.trim(),
            examples = lesson.examples.map { it.copy(sentence = it.sentence.trim(), translation = it.translation.trim()) },
            quiz = lesson.quiz.map { q ->
                q.copy(question = q.question.trim(), options = q.options.map { it.trim() }, feedback = q.feedback.trim())
            },
        )
        val prose = listOf(trimmed.rule, trimmed.yours, trimmed.trap)
        val ok = trimmed.language.length <= MAX_SHORT &&
            prose.all { it.isNotEmpty() && it.length <= MAX_PROSE } &&
            trimmed.examples.size == Lesson.EXAMPLES &&
            trimmed.examples.all { it.sentence.isNotEmpty() && it.translation.isNotEmpty() &&
                it.sentence.length <= MAX_LINE && it.translation.length <= MAX_LINE } &&
            trimmed.quiz.size == Lesson.QUESTIONS &&
            trimmed.quiz.all { q ->
                q.question.isNotEmpty() && q.question.length <= MAX_LINE &&
                    q.feedback.isNotEmpty() && q.feedback.length <= MAX_LINE &&
                    q.options.size in 2..4 && q.options.all { it.isNotEmpty() && it.length <= MAX_SHORT } &&
                    q.options.map { it.lowercase() }.distinct().size == q.options.size &&
                    q.correct in q.options.indices
            }
        if (!ok) invalid(CheckFailure.Message.InvalidReply)
        return trimmed
    }

    private fun invalid(message: CheckFailure.Message): Nothing =
        throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = message)

    companion object {
        const val PROMPT = "lab_teach_me_prompt.md"
        const val SCHEMA = "lab_teach_me_schema.json"
        const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_REPLY = 16_384
        private const val MAX_PROSE = 600
        private const val MAX_LINE = 300
        private const val MAX_SHORT = 80
        private const val MAX_SENTENCE = 600

        /** Reads both assets through [read], by name. */
        fun load(read: (String) -> String) = LessonPrompt(read(PROMPT), read(SCHEMA))

        private val json = Json { ignoreUnknownKeys = false }
    }
}
