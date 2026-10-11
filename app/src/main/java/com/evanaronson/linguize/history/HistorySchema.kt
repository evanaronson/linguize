package com.evanaronson.linguize.history

import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Verdict

/*
 * The SQLite shape of history, and the mapping between rows and records. Pure Kotlin
 * with no Android in it, so the mapping is unit-tested; SqliteHistoryStore runs it.
 *
 * Columns are named exactly like the fields of the records, so a row in a SQLite browser
 * reads like the JSON it exports as. Enums are stored as their tokens ([Tokens]) and read
 * leniently. `attempts` is JSON (it's kept for re-deriving things later, never queried);
 * everything else is a real column. Identifiers are always double-quoted because `end` is
 * an SQL keyword.
 *
 * Versions: 1 was the first history build (enums stored by constant name, no `status`
 * at first, an attempt's answer under `verdict`); 2 adds `nativeLanguage` and stores
 * tokens and `raw`; 3 stores the requested language as a code and an attempt's failure
 * as a token; 4 records no checks from the app's own "Try it" card, and an attempt keeps
 * the language and settings it ran with (a card is one session, whatever changes). [upgrade] says how to get from each older version to this one.
 */
internal object HistorySchema {
    const val NAME = "history.db"

    /**
     * The database's version, for SQLiteOpenHelper. Separate from [SessionRecord.SCHEMA],
     * which is the shape of a row as it would travel to a server.
     */
    const val VERSION = 4

    const val SESSIONS = "sessions"
    const val SUGGESTIONS = "suggestions"

    /** In [SessionRecord]'s field order; a test holds the two together. */
    val SESSION_COLUMNS = listOf(
        "id", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "startedAt", "closedAt",
        "origin", "hostApp", "requestedLanguage", "nativeLanguage", "text", "textHash", "finalText", "outcome",
        "punctuation", "judgments", "provider", "model", "promptHash", "appVersion", "attempts", "meaning", "status",
    )

    /** In [SuggestionRecord]'s field order; a test holds the two together. */
    val SUGGESTION_COLUMNS = listOf(
        "id", "sessionId", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "attempt",
        "kind", "start", "end", "fromText", "toText", "why", "decision", "decidedAt",
    )

    val CREATE = listOf(
        """
        CREATE TABLE $SESSIONS (
            "id" TEXT PRIMARY KEY NOT NULL,
            "deviceId" TEXT NOT NULL,
            "schema" INTEGER NOT NULL,
            "createdAt" INTEGER NOT NULL,
            "updatedAt" INTEGER NOT NULL,
            "deletedAt" INTEGER,
            "startedAt" INTEGER NOT NULL,
            "closedAt" INTEGER,
            "origin" TEXT NOT NULL,
            "hostApp" TEXT,
            "requestedLanguage" TEXT,
            "nativeLanguage" TEXT,
            "text" TEXT NOT NULL,
            "textHash" TEXT NOT NULL,
            "finalText" TEXT,
            "outcome" TEXT,
            "punctuation" TEXT NOT NULL,
            "judgments" TEXT NOT NULL,
            "provider" TEXT NOT NULL,
            "model" TEXT NOT NULL,
            "promptHash" TEXT NOT NULL,
            "appVersion" TEXT NOT NULL,
            "attempts" TEXT NOT NULL,
            "meaning" TEXT,
            "status" TEXT
        )
        """,
        """
        CREATE TABLE $SUGGESTIONS (
            "id" TEXT PRIMARY KEY NOT NULL,
            "sessionId" TEXT NOT NULL REFERENCES $SESSIONS("id") ON DELETE CASCADE,
            "deviceId" TEXT NOT NULL,
            "schema" INTEGER NOT NULL,
            "createdAt" INTEGER NOT NULL,
            "updatedAt" INTEGER NOT NULL,
            "deletedAt" INTEGER,
            "attempt" INTEGER NOT NULL,
            "kind" TEXT NOT NULL,
            "start" INTEGER NOT NULL,
            "end" INTEGER NOT NULL,
            "fromText" TEXT NOT NULL,
            "toText" TEXT NOT NULL,
            "why" TEXT,
            "decision" TEXT NOT NULL,
            "decidedAt" INTEGER NOT NULL
        )
        """,
        // Recent, the count and the oldest date: kept rows, by start.
        """CREATE INDEX sessions_kept ON $SESSIONS("deletedAt", "startedAt")""",
        // reusable(): the same text, recently.
        """CREATE INDEX sessions_text ON $SESSIONS("textHash", "startedAt")""",
        // A session's suggestions, for Recent's counts, the detail view and the cascade.
        """CREATE INDEX suggestions_session ON $SUGGESTIONS("sessionId")""",
    ).map { it.trimIndent() }

    /**
     * Adds a session or updates it in place, but never changes one that's closed: a closed
     * session is final, so a save that arrives after close() is dropped. (An update, not a
     * REPLACE, which would delete the row and cascade to its suggestions.)
     */
    val SAVE_SESSION = upsert(SESSIONS, SESSION_COLUMNS) + """ WHERE $SESSIONS."closedAt" IS NULL"""

    /** Adds or updates a session whatever its state: closing writes the final version. */
    val CLOSE_SESSION = upsert(SESSIONS, SESSION_COLUMNS)

    val INSERT_SUGGESTION = "INSERT OR REPLACE INTO $SUGGESTIONS (${quoted(SUGGESTION_COLUMNS)}) " +
        "VALUES (${SUGGESTION_COLUMNS.joinToString { "?" }})"

    /**
     * Closes every open session but [keep] of them, as abandoned; binds now, now, then the
     * ids to keep: the sessions this process opened. No clock decides what's abandoned, so
     * a clock set back between two runs can't leave a session open for good.
     */
    fun markAbandoned(keep: Int): String {
        val kept = if (keep == 0) "" else """ AND "id" NOT IN (${List(keep) { "?" }.joinToString()})"""
        return """
        UPDATE $SESSIONS SET "closedAt" = ?, "updatedAt" = ?, "outcome" = '${Outcome.Abandoned.token}'
        WHERE "closedAt" IS NULL$kept
        """.trimIndent()
    }

    /** Sessions Recent can show: kept and closed. A card still open isn't counted until it closes. */
    const val COUNT = """SELECT COUNT(*) AS "n" FROM $SESSIONS WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL"""

    /** The oldest session Recent can show, as [COUNT] counts them. */
    const val SINCE = """SELECT MIN("startedAt") AS "since" FROM $SESSIONS WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL"""

    /**
     * Closed, kept sessions, newest first, with counts from the suggestions that weren't
     * superseded: those are the ones decided at the close. Only the start of each text is
     * read: a row shows one line of it.
     */
    fun recent(limit: Int) = """
        SELECT s."id", s."startedAt", s."origin", s."hostApp", s."text", s."outcome", s."status",
            COALESCE(SUM(g."kind" = '$FIX'), 0) AS "fixes",
            COALESCE(SUM(g."kind" = '$NATURAL'), 0) AS "rewordings",
            COALESCE(SUM(g."decision" = '$ACCEPTED'), 0) AS "taken"
        FROM (
            SELECT "id", "startedAt", "origin", "hostApp", substr("text", 1, ${SessionSummary.TEXT_PREFIX}) AS "text", "outcome", "status"
            FROM $SESSIONS
            WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL
            ORDER BY "startedAt" DESC
            LIMIT $limit
        ) AS s
        LEFT JOIN $SUGGESTIONS AS g
            ON g."sessionId" = s."id" AND g."deletedAt" IS NULL AND g."decision" != '$SUPERSEDED'
        GROUP BY s."id"
        ORDER BY s."startedAt" DESC
    """.trimIndent()

    private val FIX = EditKind.Fix.token
    private val NATURAL = EditKind.Natural.token
    private val ACCEPTED = Decision.Accepted.token
    private val SUPERSEDED = Decision.Superseded.token

    const val SESSION = """SELECT * FROM $SESSIONS WHERE "id" = ? AND "deletedAt" IS NULL"""

    const val SESSION_SUGGESTIONS =
        """SELECT * FROM $SUGGESTIONS WHERE "sessionId" = ? AND "deletedAt" IS NULL ORDER BY "attempt", "start", "end" """

    const val ALL_SESSIONS = """SELECT * FROM $SESSIONS WHERE "deletedAt" IS NULL ORDER BY "startedAt" """

    /**
     * Candidates for reuse of [key], newest first; binds [reusableArgs]. Whether the last
     * attempt succeeded is read from `attempts` by the caller.
     */
    fun reusable(key: ReuseKey): String {
        // Null matches null: an auto-detected check matches an auto-detected one.
        fun same(column: String, value: String?) = if (value == null) "\"$column\" IS NULL" else "\"$column\" = ?"
        return """
        SELECT * FROM $SESSIONS
        WHERE "textHash" = ?
            AND ${same("requestedLanguage", key.requestedLanguage)} AND ${same("nativeLanguage", key.settings.nativeLanguage)}
            AND "punctuation" = ? AND "judgments" = ? AND "provider" = ? AND "model" = ? AND "promptHash" = ?
            AND "startedAt" >= ?
            AND "closedAt" IS NOT NULL AND "deletedAt" IS NULL AND "outcome" != '${Outcome.Failed.token}'
        ORDER BY "startedAt" DESC
        LIMIT 20
        """.trimIndent()
    }

    /** The values [reusable] binds, in order. */
    fun reusableArgs(key: ReuseKey, since: Long): List<String> = with(key.settings) {
        listOfNotNull(key.textHash, key.requestedLanguage, nativeLanguage, punctuation, judgments, provider, model, promptHash) + since.toString()
    }

    const val DELETE_SESSION = """DELETE FROM $SESSIONS WHERE "id" = ?"""

    /** Clearing everything; the suggestions go by cascade too, this is just quicker. */
    val CLEAR = listOf("DELETE FROM $SUGGESTIONS", "DELETE FROM $SESSIONS")

    /** Every table, for starting over when an old database can't be upgraded. */
    val DROP = listOf("DROP TABLE IF EXISTS $SUGGESTIONS", "DROP TABLE IF EXISTS $SESSIONS")

    /**
     * The columns of a session that each attempt keeps for itself from schema 4 on: the
     * language and settings it ran with. Named alike in the row and in an attempt's JSON.
     */
    val ATTEMPT_CONTEXT = listOf("requestedLanguage", "nativeLanguage", "punctuation", "judgments", "provider", "model", "promptHash")

    /** A session's attempts, with the language and settings they ran with, for rewriting them on an upgrade. */
    val ALL_ATTEMPTS = """SELECT "id", ${quoted(ATTEMPT_CONTEXT)}, "attempts" FROM $SESSIONS"""

    const val SET_ATTEMPTS = """UPDATE $SESSIONS SET "attempts" = ? WHERE "id" = ?"""

    /**
     * The statements that bring a database from version [from] to [VERSION], given the
     * columns its `sessions` table has; null when there's no way (then it's made afresh).
     * Attempts are rewritten separately, row by row ([upgradeAttempts]).
     *
     * Version 1 came in two shapes: the first builds had no `status` column, and builds
     * from the token change on added it without changing the version. Both had enums by
     * constant name ("Accepted", "Gemini"), which become tokens here, so the counts in
     * Recent and the reuse lookup, which compare tokens, see every row. Versions 1 and 2
     * kept the requested language by its English name, which becomes its code. Versions 1
     * to 3 recorded checks made in the app's "Try it" card, which aren't history: those
     * sessions are deleted, with their suggestions (explicitly, though the foreign key
     * cascades too: the delete must not depend on the connection's foreign-key setting).
     */
    fun upgrade(from: Int, sessionColumns: Set<String>): List<String>? {
        if (from !in 1 until VERSION) return null
        return buildList {
            if (from == 1) {
                if ("status" !in sessionColumns) add("""ALTER TABLE $SESSIONS ADD COLUMN "status" TEXT""")
                if ("nativeLanguage" !in sessionColumns) add("""ALTER TABLE $SESSIONS ADD COLUMN "nativeLanguage" TEXT""")
                add(renaming(SESSIONS, "origin", Origin.tokens.renames()))
                add(renaming(SESSIONS, "outcome", Outcome.tokens.renames()))
                add(renaming(SESSIONS, "status", Verdict.Status.tokens.renames()))
                add(renaming(SESSIONS, "punctuation", Punctuation.tokens.renames()))
                add(renaming(SESSIONS, "judgments", Judgments.tokens.renames()))
                add(renaming(SESSIONS, "provider", VERSION_1_PROVIDERS))
                add(renaming(SUGGESTIONS, "kind", EditKind.tokens.renames()))
                add(renaming(SUGGESTIONS, "decision", Decision.tokens.renames()))
            }
            add(renaming(SESSIONS, "requestedLanguage", Language.all.associate { it.name to it.code }))
            addAll(DELETE_UNRECORDED)
            // Rows now have this build's shape.
            add("""UPDATE $SESSIONS SET "schema" = ${SessionRecord.SCHEMA}""")
            add("""UPDATE $SUGGESTIONS SET "schema" = ${SessionRecord.SCHEMA}""")
        }
    }

    /**
     * The providers version 1 stored, by constant name, with their tokens. Spelled out
     * rather than read from `Provider`, which history doesn't depend on; version 1 is done,
     * so this never grows. A test holds it to `Provider`'s tokens.
     */
    val VERSION_1_PROVIDERS = mapOf("Gemini" to "gemini", "OpenAI" to "openai")

    /** Rewrites [column] by [renames] (from old value to new); anything else is left as it is. */
    private fun renaming(table: String, column: String, renames: Map<String, String>): String {
        val cases = renames.entries.joinToString(" ") { (from, to) -> "WHEN '$from' THEN '$to'" }
        val names = renames.keys.joinToString { "'$it'" }
        return """UPDATE $table SET "$column" = CASE "$column" $cases END WHERE "$column" IN ($names)"""
    }

    /**
     * Deletes the sessions of origins that aren't [recorded][Origin.recorded] (the tester,
     * which builds before version 4 recorded) and their suggestions. By token, after version
     * 1's names have become tokens.
     */
    val DELETE_UNRECORDED: List<String> = run {
        val origins = Origin.entries.filterNot { it.recorded }.joinToString { "'${it.token}'" }
        listOf(
            """DELETE FROM $SUGGESTIONS WHERE "sessionId" IN (SELECT "id" FROM $SESSIONS WHERE "origin" IN ($origins))""",
            """DELETE FROM $SESSIONS WHERE "origin" IN ($origins)""",
        )
    }

    const val DELETE_SUGGESTIONS_OF = """DELETE FROM $SUGGESTIONS WHERE "sessionId" = ?"""

    private fun quoted(columns: List<String>) = columns.joinToString { "\"$it\"" }

    private fun upsert(table: String, columns: List<String>) =
        "INSERT INTO $table (${quoted(columns)}) VALUES (${columns.joinToString { "?" }}) " +
            "ON CONFLICT(\"id\") DO UPDATE SET " +
            columns.filter { it != "id" }.joinToString { "\"$it\" = excluded.\"$it\"" }
}

/** One column of the current row, by name. SQLite's cursor in the app; a map in tests. */
internal interface Row {
    fun stringOrNull(column: String): String?

    fun longOrNull(column: String): Long?

    fun string(column: String): String = checkNotNull(stringOrNull(column)) { "$column is null" }

    fun long(column: String): Long = checkNotNull(longOrNull(column)) { "$column is null" }
}

/** The values to bind for [HistorySchema.SESSION_COLUMNS], by column. */
internal fun SessionRecord.values(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "deviceId" to deviceId,
    "schema" to schema,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "deletedAt" to deletedAt,
    "startedAt" to startedAt,
    "closedAt" to closedAt,
    "origin" to origin?.let(Origin::token),
    "hostApp" to hostApp,
    "requestedLanguage" to requestedLanguage,
    "nativeLanguage" to nativeLanguage,
    "text" to text,
    "textHash" to textHash,
    "finalText" to finalText,
    "outcome" to outcome?.let(Outcome::token),
    "punctuation" to punctuation,
    "judgments" to judgments,
    "provider" to provider,
    "model" to model,
    "promptHash" to promptHash,
    "appVersion" to appVersion,
    "attempts" to encodeAttempts(attempts),
    "meaning" to meaning,
    "status" to status?.let(Verdict.Status::token),
)

/** The values to bind for [HistorySchema.SUGGESTION_COLUMNS], by column. */
internal fun SuggestionRecord.values(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "sessionId" to sessionId,
    "deviceId" to deviceId,
    "schema" to schema,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "deletedAt" to deletedAt,
    "attempt" to attempt,
    "kind" to kind.token,
    "start" to start,
    "end" to end,
    "fromText" to fromText,
    "toText" to toText,
    "why" to why,
    "decision" to decision.token,
    "decidedAt" to decidedAt,
)

/**
 * A session row as a record. Lenient where a newer build could have written something
 * this one doesn't know: an unknown origin, outcome or status reads as null, unreadable
 * attempts as none. For showing and reusing only; the export reads the rows themselves.
 */
internal fun sessionRecord(row: Row) = SessionRecord(
    id = row.string("id"),
    deviceId = row.string("deviceId"),
    schema = row.long("schema").toInt(),
    createdAt = row.long("createdAt"),
    updatedAt = row.long("updatedAt"),
    deletedAt = row.longOrNull("deletedAt"),
    startedAt = row.long("startedAt"),
    closedAt = row.longOrNull("closedAt"),
    origin = Origin.tokens.decode(row.string("origin")),
    hostApp = row.stringOrNull("hostApp"),
    requestedLanguage = row.stringOrNull("requestedLanguage"),
    nativeLanguage = row.stringOrNull("nativeLanguage"),
    text = row.string("text"),
    textHash = row.string("textHash"),
    finalText = row.stringOrNull("finalText"),
    outcome = Outcome.tokens.decode(row.stringOrNull("outcome")),
    punctuation = row.string("punctuation"),
    judgments = row.string("judgments"),
    provider = row.string("provider"),
    model = row.string("model"),
    promptHash = row.string("promptHash"),
    appVersion = row.string("appVersion"),
    attempts = decodeAttempts(row.string("attempts")),
    meaning = row.stringOrNull("meaning"),
    status = Verdict.Status.tokens.decode(row.stringOrNull("status")),
)

/**
 * A suggestion row as a record, or null when its kind or decision is one this build
 * doesn't know: a suggestion can't be shown or counted without them.
 */
internal fun suggestionRecord(row: Row): SuggestionRecord? {
    val kind = EditKind.tokens.decode(row.string("kind")) ?: return null
    val decision = Decision.tokens.decode(row.string("decision")) ?: return null
    return SuggestionRecord(
        id = row.string("id"),
        sessionId = row.string("sessionId"),
        deviceId = row.string("deviceId"),
        schema = row.long("schema").toInt(),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        deletedAt = row.longOrNull("deletedAt"),
        attempt = row.long("attempt").toInt(),
        kind = kind,
        start = row.long("start").toInt(),
        end = row.long("end").toInt(),
        fromText = row.string("fromText"),
        toText = row.string("toText"),
        why = row.stringOrNull("why"),
        decision = decision,
        decidedAt = row.long("decidedAt"),
    )
}

/** A row of [HistorySchema.recent]. */
internal fun sessionSummary(row: Row) = SessionSummary(
    id = row.string("id"),
    startedAt = row.long("startedAt"),
    origin = Origin.tokens.decode(row.string("origin")),
    hostApp = row.stringOrNull("hostApp"),
    text = row.string("text"),
    outcome = Outcome.tokens.decode(row.stringOrNull("outcome")),
    fixes = row.long("fixes").toInt(),
    rewordings = row.long("rewordings").toInt(),
    taken = row.long("taken").toInt(),
    status = Verdict.Status.tokens.decode(row.stringOrNull("status")),
)

/** Whether a session's answer can be shown again: its last attempt came back with one. */
internal fun SessionRecord.lastAttemptSucceeded(): Boolean = attempts.lastOrNull()?.succeeded == true

/** Columns that hold numbers; every other column holds text. For writing rows out as JSON. */
internal val INTEGER_COLUMNS = setOf(
    "schema", "createdAt", "updatedAt", "deletedAt", "startedAt", "closedAt", "attempt", "start", "end", "decidedAt",
)
