package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.WritingProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The JSON side of history: the `attempts` column and the export. Kept apart from
 * HistorySchema so that the row mapping can be read without the serialization plugin.
 */

/** Every field written, unknown ones skipped on reading. */
internal val historyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val attemptList = ListSerializer(Attempt.serializer())

internal fun encodeAttempts(attempts: List<Attempt>): String = historyJson.encodeToString(attemptList, attempts)

/** The `attempts` column read back; empty when it can't be read, which only loses the audit trail. */
internal fun decodeAttempts(stored: String): List<Attempt> = try {
    historyJson.decodeFromString(attemptList, stored)
} catch (_: SerializationException) {
    emptyList()
} catch (_: IllegalArgumentException) {
    emptyList()
}

/**
 * The `attempts` column of an older row in this version's shape: an attempt's answer moves
 * from `verdict` to `raw` (version 1), its failure goes from the reason's constant name to
 * its token (versions 1 and 2, see [LEGACY_FAILURES]), and it gets the language and
 * settings it ran with (versions 1 to 3, which ran every attempt of a session with the
 * session's: [context] has them by field, from the row; a null one, such as auto-detect,
 * reads as null anyway, so it isn't added). Anything else, a field an attempt has already,
 * and a column that can't be read, are left as they are.
 */
internal fun upgradeAttempts(stored: String, context: Map<String, String?> = emptyMap()): String {
    val attempts = try {
        historyJson.parseToJsonElement(stored) as? JsonArray ?: return stored
    } catch (_: SerializationException) {
        return stored
    }
    val upgraded = attempts.map { attempt -> if (attempt is JsonObject) upgradeAttempt(attempt, context) else attempt }
    return if (upgraded == attempts) stored else JsonArray(upgraded).toString()
}

private fun upgradeAttempt(attempt: JsonObject, context: Map<String, String?>): JsonObject {
    val failure = (attempt["failure"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val token = LEGACY_FAILURES[failure]
    val added = context.filter { (field, value) -> value != null && field !in attempt }.mapValues { JsonPrimitive(it.value) }
    if (token == null && added.isEmpty() && ("verdict" !in attempt || "raw" in attempt)) return attempt
    val renamed = attempt.entries.associate { (key, value) ->
        when {
            key == "verdict" && "raw" !in attempt -> "raw" to value
            key == "failure" && token != null -> "failure" to JsonPrimitive(token)
            else -> key to value
        }
    }
    return JsonObject(renamed + added)
}

/**
 * The failure reasons versions 1 and 2 kept, by constant name, with their tokens. Spelled
 * out rather than read from `CheckFailure.Reason`, which history doesn't depend on; those
 * versions are done, so this never grows. A test holds it to the reasons' tokens.
 */
internal val LEGACY_FAILURES = mapOf(
    "NoKey" to "no_key",
    "BadKey" to "bad_key",
    "BadModel" to "bad_model",
    "Offline" to "offline",
    "Timeout" to "timeout",
    "RateLimited" to "rate_limited",
    "Server" to "server",
    "BadResponse" to "bad_response",
    "TooLong" to "too_long",
    "TooMany" to "too_many",
)

/**
 * A row as JSON, column by column, exactly as it's stored: numbers as numbers, text as
 * text, and `attempts` as the JSON it holds. Nothing is decoded, so a token this build
 * doesn't know, or a field of an attempt it doesn't know, is written out unchanged.
 */
internal fun rowJson(row: Row, columns: List<String>): JsonObject = JsonObject(
    columns.associateWith { column ->
        when (column) {
            in INTEGER_COLUMNS -> row.longOrNull(column)?.let(::JsonPrimitive) ?: JsonNull
            "attempts", "writingProfiles", "assessment" -> row.stringOrNull(column)?.let(::storedJson) ?: JsonNull
            else -> row.stringOrNull(column)?.let(::JsonPrimitive) ?: JsonNull
        }
    },
)

/** [stored] as the JSON it is, or as a string when it isn't JSON. */
private fun storedJson(stored: String): JsonElement = try {
    historyJson.parseToJsonElement(stored)
} catch (_: SerializationException) {
    JsonPrimitive(stored)
}

/**
 * One line of the export: a session row with its suggestion rows, in [SessionDetail]'s
 * shape, as JSON with no line breaks.
 */
internal fun exportLine(session: JsonObject, suggestions: List<JsonObject>): String =
    JsonObject(mapOf("session" to session, "suggestions" to JsonArray(suggestions))).toString()

/** Backfill only language metadata; keep raw answers and unknown evidence fields unchanged. */
internal fun upgradeLanguageAttempts(stored: String): String {
    val attempts = try {
        historyJson.parseToJsonElement(stored) as? JsonArray ?: return stored
    } catch (_: SerializationException) {
        return stored
    }
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    return JsonArray(attempts.map { element ->
        val attempt = element as? JsonObject ?: return@map element
        val detected = detectedLanguage(attempt.text("raw"), attempt.text("failure"))
        val target = languageCode(attempt.text("requestedLanguage")) ?: detected
        JsonObject(attempt + mapOf(
            "targetLanguage" to (target?.let(::JsonPrimitive) ?: JsonNull),
            "detectedLanguage" to (detected?.let(::JsonPrimitive) ?: JsonNull),
        ))
    }).toString()
}

/** Structured metadata is nullable/empty on corruption; it must never become invented scores. */
internal fun encodeProfiles(profiles: List<WritingProfile>): String =
    historyJson.encodeToString(ListSerializer(WritingProfile.serializer()), profiles)

internal fun decodeProfiles(stored: String?): List<WritingProfile> = try {
    stored?.let { historyJson.decodeFromString(ListSerializer(WritingProfile.serializer()), it) }.orEmpty()
} catch (_: Exception) { emptyList() }

internal fun encodeAssessment(assessment: SuggestionAssessment?): String? =
    assessment?.let { historyJson.encodeToString(SuggestionAssessment.serializer(), it) }

internal fun decodeAssessment(stored: String?): SuggestionAssessment? = try {
    stored?.let { historyJson.decodeFromString(SuggestionAssessment.serializer(), it) }
        ?.takeIf { it.confidence in 0..3 && it.impact in 0..3 && it.rubricVersion.isNotBlank() && it.origin.isNotBlank() }
} catch (_: Exception) { null }
