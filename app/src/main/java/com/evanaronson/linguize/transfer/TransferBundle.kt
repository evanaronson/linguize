package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.data.chat.ChatTransferRows
import com.evanaronson.linguize.data.chat.PendingChatDeletion
import com.evanaronson.linguize.history.TransferSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The snapshot the experimental app hands over: JSON Lines, one record per line, so neither
 * side holds the whole bundle as one JSON tree. A header comes first and `end` last; a bundle
 * without its end was cut off and is refused whole. Rows travel exactly as stored (column to
 * value), files as their text, preferences with their type.
 *
 * ```
 * {"k":"header","format":1,"package":"…","appVersion":"…","historyVersion":8}
 * {"k":"session","session":{…},"suggestions":[{…}]}        one per History session
 * {"k":"excluded","sessions":["…"]}                         History deletions waiting for Undo
 * {"k":"chat","conversations":[…],"messages":[…],"pending":[{"conversation":"…","sessions":[…]}]}
 * {"k":"time","records":[{…}]}
 * {"k":"file","name":"lab_memory.json","text":"…"}          one per file, path under files/
 * {"k":"prefs","name":"settings","values":{"key":{"t":"s","v":"…"}}}
 * {"k":"unreadable","part":"chat"}                          a part the source couldn't read
 * {"k":"end"}
 * ```
 */
object TransferBundle {
    const val FORMAT = 1

    /** A preference value with its type, as SharedPreferences holds it. */
    sealed interface Pref {
        data class Str(val value: String) : Pref
        data class Bool(val value: Boolean) : Pref
        data class IntValue(val value: Int) : Pref
        data class LongValue(val value: Long) : Pref
        data class FloatValue(val value: Float) : Pref
        data class StringSet(val value: Set<String>) : Pref

        companion object {
            fun of(value: Any?): Pref? = when (value) {
                is String -> Str(value)
                is Boolean -> Bool(value)
                is Int -> IntValue(value)
                is Long -> LongValue(value)
                is Float -> FloatValue(value)
                is Set<*> -> StringSet(value.filterIsInstance<String>().toSet())
                else -> null
            }
        }
    }

    /** Everything a bundle carried, read whole before anything is merged. */
    data class Incoming(
        val format: Int,
        val sourcePackage: String,
        val appVersion: String,
        val historyVersion: Int?,
        val sessions: List<TransferSession>,
        val excludedSessions: Set<String>,
        val chat: ChatTransferRows?,
        val time: List<TimeRecord>,
        val files: Map<String, String>,
        val prefs: Map<String, Map<String, Pref>>,
        val unreadable: Set<String>,
    )

    /** Writes one bundle's lines. The caller closes [out]. */
    class Writer(out: OutputStream) {
        private val writer: java.io.Writer = out.bufferedWriter(Charsets.UTF_8)

        fun header(sourcePackage: String, appVersion: String, historyVersion: Int) = line(
            "header", "format" to JsonPrimitive(FORMAT), "package" to JsonPrimitive(sourcePackage),
            "appVersion" to JsonPrimitive(appVersion), "historyVersion" to JsonPrimitive(historyVersion),
        )

        fun session(session: TransferSession) = line(
            "session", "session" to row(session.session), "suggestions" to JsonArray(session.suggestions.map(::row)),
        )

        fun excluded(sessions: Collection<String>) = line("excluded", "sessions" to strings(sessions))

        fun chat(rows: ChatTransferRows) = line(
            "chat",
            "conversations" to JsonArray(rows.conversations.map(::row)),
            "messages" to JsonArray(rows.messages.map(::row)),
            "pending" to JsonArray(rows.pendingDeletions.map {
                JsonObject(mapOf("conversation" to JsonPrimitive(it.conversationId), "sessions" to strings(it.sessionIds)))
            }),
        )

        fun time(records: List<TimeRecord>) = line("time", "records" to JsonArray(records.map(::timeRecord)))

        fun file(name: String, text: String) = line("file", "name" to JsonPrimitive(name), "text" to JsonPrimitive(text))

        fun prefs(name: String, values: Map<String, Pref>) =
            line("prefs", "name" to JsonPrimitive(name), "values" to JsonObject(values.mapValues { pref(it.value) }))

        fun unreadable(part: String) = line("unreadable", "part" to JsonPrimitive(part))

        fun end() {
            line("end")
            writer.flush()
        }

        private fun line(kind: String, vararg fields: Pair<String, JsonElement>) {
            writer.write(JsonObject(mapOf("k" to JsonPrimitive(kind)) + fields).toString())
            writer.write("\n")
        }
    }

    /** Reads a whole bundle. Throws [IOException] when it is cut off, malformed or of another format. */
    fun read(input: InputStream): Incoming {
        val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
        var format: Int? = null
        var sourcePackage = ""
        var appVersion = ""
        var historyVersion: Int? = null
        val sessions = mutableListOf<TransferSession>()
        val excluded = mutableSetOf<String>()
        var chat: ChatTransferRows? = null
        val time = mutableListOf<TimeRecord>()
        val files = linkedMapOf<String, String>()
        val prefs = linkedMapOf<String, Map<String, Pref>>()
        val unreadable = mutableSetOf<String>()
        var ended = false
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (ended) throw IOException("Data after the end of the bundle")
                val record = json.parseToJsonElement(line).jsonObject
                val kind = record.string("k")
                if (format == null && kind != "header") throw IOException("The bundle has no header")
                when (kind) {
                    "header" -> {
                        format = record.getValue("format").jsonPrimitive.intOrNull
                        if (format != FORMAT) throw IOException("Unknown bundle format $format")
                        sourcePackage = record.string("package")
                        appVersion = record.string("appVersion")
                        historyVersion = record["historyVersion"]?.jsonPrimitive?.intOrNull
                    }
                    "session" -> sessions += TransferSession(
                        rawRow(record.getValue("session")),
                        record.getValue("suggestions").jsonArray.map(::rawRow),
                    )
                    "excluded" -> excluded += record.getValue("sessions").jsonArray.map { it.jsonPrimitive.content }
                    "chat" -> chat = ChatTransferRows(
                        record.getValue("conversations").jsonArray.map(::rawRow),
                        record.getValue("messages").jsonArray.map(::rawRow),
                        record.getValue("pending").jsonArray.map { entry ->
                            val pending = entry.jsonObject
                            PendingChatDeletion(pending.string("conversation"), pending.getValue("sessions").jsonArray.map { it.jsonPrimitive.content })
                        },
                    )
                    "time" -> time += record.getValue("records").jsonArray.map { timeRecord(it.jsonObject) }
                    "file" -> files[record.string("name")] = record.string("text")
                    "prefs" -> prefs[record.string("name")] = record.getValue("values").jsonObject.mapValues { pref(it.value.jsonObject) }
                    "unreadable" -> unreadable += record.string("part")
                    "end" -> ended = true
                    // A newer source may add kinds; they are not this build's to merge.
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Unreadable bundle", e)
        }
        if (!ended) throw IOException("The bundle was cut off")
        return Incoming(
            checkNotNull(format), sourcePackage, appVersion, historyVersion, sessions, excluded, chat, time, files, prefs, unreadable,
        )
    }

    private val json = Json

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))

    /** A stored row: text stays text, integers integers. */
    internal fun row(row: Map<String, Any?>): JsonObject = JsonObject(row.mapValues { (_, value) ->
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value.toLong())
            is Double -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unexpected stored value ${value::class.simpleName}")
        }
    })

    internal fun rawRow(element: JsonElement): Map<String, Any?> = element.jsonObject.mapValues { (_, value) ->
        when {
            value is JsonNull -> null
            value !is JsonPrimitive -> throw IllegalArgumentException("Nested value in a stored row")
            value.isString -> value.content
            else -> value.longOrNull ?: value.doubleOrNull ?: throw IllegalArgumentException("Unexpected value ${value.content}")
        }
    }

    private fun timeRecord(record: TimeRecord) = JsonObject(mapOf(
        "sessionId" to JsonPrimitive(record.sessionId),
        "origin" to JsonPrimitive(record.origin),
        "language" to JsonPrimitive(record.language),
        "writingMs" to JsonPrimitive(record.writingMs),
        "reviewMs" to JsonPrimitive(record.reviewMs),
        "words" to JsonPrimitive(record.words),
        "changesShown" to JsonPrimitive(record.changesShown),
        "changesAccepted" to JsonPrimitive(record.changesAccepted),
        "at" to JsonPrimitive(record.at),
        "rules" to JsonPrimitive(record.rules),
        "messageId" to JsonPrimitive(record.messageId),
    ))

    private fun timeRecord(record: JsonObject): TimeRecord {
        fun primitive(key: String) = record.getValue(key).jsonPrimitive
        fun text(key: String) = primitive(key).takeUnless { it is JsonNull }?.content
        return TimeRecord(
            sessionId = record.string("sessionId"),
            origin = record.string("origin"),
            language = text("language"),
            writingMs = primitive("writingMs").longOrNull,
            reviewMs = checkNotNull(primitive("reviewMs").longOrNull),
            words = checkNotNull(primitive("words").intOrNull),
            changesShown = checkNotNull(primitive("changesShown").intOrNull),
            changesAccepted = checkNotNull(primitive("changesAccepted").intOrNull),
            at = checkNotNull(primitive("at").longOrNull),
            rules = checkNotNull(primitive("rules").intOrNull),
            messageId = text("messageId"),
        )
    }

    private fun pref(pref: Pref): JsonObject = when (pref) {
        is Pref.Str -> typed("s", JsonPrimitive(pref.value))
        is Pref.Bool -> typed("b", JsonPrimitive(pref.value))
        is Pref.IntValue -> typed("i", JsonPrimitive(pref.value))
        is Pref.LongValue -> typed("l", JsonPrimitive(pref.value))
        is Pref.FloatValue -> typed("f", JsonPrimitive(pref.value))
        is Pref.StringSet -> typed("ss", strings(pref.value.sorted()))
    }

    private fun typed(type: String, value: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(type), "v" to value))

    private fun pref(stored: JsonObject): Pref {
        val value = stored.getValue("v")
        return when (stored.string("t")) {
            "s" -> Pref.Str(value.jsonPrimitive.content)
            "b" -> Pref.Bool(checkNotNull(value.jsonPrimitive.booleanOrNull))
            "i" -> Pref.IntValue(checkNotNull(value.jsonPrimitive.intOrNull))
            "l" -> Pref.LongValue(checkNotNull(value.jsonPrimitive.longOrNull))
            "f" -> Pref.FloatValue(checkNotNull(value.jsonPrimitive.floatOrNull))
            "ss" -> Pref.StringSet(value.jsonArray.map { it.jsonPrimitive.content }.toSet())
            else -> throw IllegalArgumentException("Unknown preference type")
        }
    }
}
