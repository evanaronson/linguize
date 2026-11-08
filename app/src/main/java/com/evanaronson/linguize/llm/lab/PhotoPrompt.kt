package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the model read in a photo. Reading assistance only: no edits, nothing to put back. */
@Serializable
data class PhotoReading(
    /** False when there was no text the model could read with confidence. */
    val legible: Boolean,
    /** The text's language, named in the reader's language. */
    val language: String,
    val transcription: String,
    val summary: String,
    val translation: String,
    val actions: List<PhotoAction>,
    val words: List<PhotoWord>,
)

/** One thing the text asks the reader to do. Empty [deadline] or [amount] when the text gives none. */
@Serializable
data class PhotoAction(val task: String, val deadline: String, val amount: String)

/** A word or phrase from the transcription, explained for a learner. */
@Serializable
data class PhotoWord(val word: String, val explanation: String)

/**
 * The "Understand a photo" job: its own instructions and schema (assets
 * lab_share_photo_prompt.md and lab_share_photo_schema.json), separate from the check.
 */
class PhotoPrompt(val system: String, schemaJson: String) {
    val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    fun request(image: ImageInput, native: String) = MultimodalRequest(
        system = system,
        user = buildJsonObject {
            put("explainIn", native)
            put("task", "Read the text in the attached image.")
        }.toString(),
        image = image,
        schema = schema,
        name = "photo_reading",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /** The answer, trimmed and checked against the limits. Throws CheckFailure for anything unusable. */
    fun read(raw: String): PhotoReading = try {
        if (raw.length > MAX_RAW_CHARS) throw invalid()
        val parsed = json.decodeFromString<PhotoReading>(raw)
        val reading = parsed.copy(
            language = parsed.language.trim(),
            transcription = parsed.transcription.trim(),
            summary = parsed.summary.trim(),
            translation = parsed.translation.trim(),
            actions = parsed.actions
                .map { PhotoAction(it.task.trim(), it.deadline.trim(), it.amount.trim()) }
                .filter { it.task.isNotEmpty() },
            words = parsed.words
                .map { PhotoWord(it.word.trim(), it.explanation.trim()) }
                .filter { it.word.isNotEmpty() && it.explanation.isNotEmpty() },
        )
        when {
            !reading.legible -> UNREADABLE
            reading.transcription.isEmpty() || reading.translation.isEmpty() -> throw invalid()
            reading.transcription.length > MAX_TEXT_CHARS || reading.translation.length > MAX_TEXT_CHARS -> throw invalid()
            reading.summary.length > MAX_NOTE_CHARS || reading.language.length > 100 -> throw invalid()
            reading.actions.size > MAX_ACTIONS || reading.words.size > MAX_WORDS -> throw invalid()
            reading.actions.any { it.task.length > MAX_NOTE_CHARS || it.deadline.length > 200 || it.amount.length > 200 } -> throw invalid()
            reading.words.any { it.word.length > 200 || it.explanation.length > MAX_NOTE_CHARS } -> throw invalid()
            else -> reading
        }
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    }

    companion object {
        const val PROMPT_ASSET = "lab_share_photo_prompt.md"
        const val SCHEMA_ASSET = "lab_share_photo_schema.json"

        /** A page of text, its translation and the notes, with room for thinking on Gemini. */
        const val MAX_OUTPUT_TOKENS = 8192
        const val MAX_RAW_CHARS = 65_536
        const val MAX_TEXT_CHARS = 20_000
        const val MAX_NOTE_CHARS = 2_000
        const val MAX_ACTIONS = 8
        const val MAX_WORDS = 10

        /** What every unreadable photo becomes, whatever else the model put in the fields. */
        val UNREADABLE = PhotoReading(false, "", "", "", "", emptyList(), emptyList())

        fun load(read: (String) -> String) = PhotoPrompt(read(PROMPT_ASSET), read(SCHEMA_ASSET))

        private val json = Json { ignoreUnknownKeys = false }

        private fun invalid() = CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)
    }
}
