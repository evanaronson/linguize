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
 * the language and settings it ran with (a card is one session, whatever changes).
 * Version 5 adds the paging index. Version 6 adds target/detected language columns
 * and backfills attempts from saved answers (row schema 5). Version 7 adds request-time
 * writing profiles and nullable suggestion assessments (row schema 6). [upgrade] gives each step.
 */
internal object HistorySchema {
    const val NAME = "history.db"

    /**
     * The database's version, for SQLiteOpenHelper. Separate from [SessionRecord.SCHEMA],
     * which is the shape of a row as it would travel to a server.
     */
    const val VERSION = 7

    const val SESSIONS = "sessions"
    const val SUGGESTIONS = "suggestions"

    /** Matches the keyset order without scanning open or deleted sessions. */
    const val PAGE_INDEX = """CREATE INDEX IF NOT EXISTS sessions_page ON sessions("startedAt" DESC, "id" DESC) WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL"""

    /** In [SessionRecord]'s field order; a test holds the two together. */
    val SESSION_COLUMNS = listOf(
        "id", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "startedAt", "closedAt",
        "origin", "hostApp", "requestedLanguage", "nativeLanguage", "text", "textHash", "finalText", "outcome",
        "punctuation", "judgments", "provider", "model", "promptHash", "appVersion", "attempts", "meaning", "status", "targetLanguage", "detectedLanguage", "writingProfiles",
    )

    /** In [SuggestionRecord]'s field order; a test holds the two together. */
    val SUGGESTION_COLUMNS = listOf(
        "id", "sessionId", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "attempt",
        "kind", "start", "end", "fromText", "toText", "why", "decision", "decidedAt", "assessment",
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
            "status" TEXT,
            "targetLanguage" TEXT,
            "detectedLanguage" TEXT,
            "writingProfiles" TEXT NOT NULL DEFAULT '[]'
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
            "decidedAt" INTEGER NOT NULL,
            "assessment" TEXT
        )
        """,
        // Recent, the count and the oldest date: kept rows, by start.
        """CREATE INDEX sessions_kept ON $SESSIONS("deletedAt", "startedAt")""",
        PAGE_INDEX,
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
        UPDATE $SESSIONS SET "closedAt" = ?, "updatedAt" = MAX("updatedAt", ?), "outcome" = '${Outcome.Abandoned.token}'
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
    fun recent(limit: Int, before: Boolean = false) = """
        SELECT s."id", s."startedAt", s."origin", s."hostApp", s."text", s."outcome", s."status", s."requestedLanguage", s."targetLanguage", s."detectedLanguage",
            COALESCE(SUM(g."kind" = '$FIX'), 0) AS "fixes",
            COALESCE(SUM(g."kind" = '$NATURAL'), 0) AS "rewordings",
            COALESCE(SUM(g."decision" = '$ACCEPTED'), 0) AS "taken"
        FROM (
            SELECT "id", "startedAt", "origin", "hostApp", substr("text", 1, ${SessionSummary.TEXT_PREFIX}) AS "text", "outcome", "status", "requestedLanguage", "targetLanguage", "detectedLanguage"
            FROM $SESSIONS
            WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL
                ${if (before) """AND ("startedAt", "id") < (?, ?)""" else ""}
            ORDER BY "startedAt" DESC, "id" DESC
            LIMIT ${limit.coerceAtLeast(0)}
        ) AS s
        LEFT JOIN $SUGGESTIONS AS g
            ON g."sessionId" = s."id" AND g."deletedAt" IS NULL AND g."decision" != '$SUPERSEDED'
                AND g."kind" IN ($KNOWN_KINDS) AND g."decision" IN ($KNOWN_DECISIONS)
        GROUP BY s."id"
        ORDER BY s."startedAt" DESC, s."id" DESC
    """.trimIndent()

    private val FIX = EditKind.Fix.token
    private val NATURAL = EditKind.Natural.token
    private val ACCEPTED = Decision.Accepted.token
    private val SUPERSEDED = Decision.Superseded.token
    pr…51323 tokens truncated…  @Test
    fun recentIsNewestFirstWithTheStartOfEachText() = runBlocking<Unit> {
        val long = "a".repeat(1_000)
        store.close(open("s1", 1_000, text = long).closed(1_500), emptyList())
        store.close(open("s2", 2_000).closed(2_500), emptyList())
        val recent = store.recent(10).first()
        assertEquals(listOf("s2", "s1"), recent.map { it.id })
        assertEquals(SessionSummary.TEXT_PREFIX, recent[1].text.length)
        assertEquals(listOf("s2"), store.recent(1).first().map { it.id })
        assertEquals(long, store.detail("s1")!!.session.text)
    }

    @Test
    fun recentCountsOnlySuggestionKindsAndDecisionsThisBuildCanRead() = runBlocking<Unit> {
        val closed = open("s1", 5_000).answered(5_500).closed(6_000)
        store.close(closed, listOf(
            suggestion(closed, "known", EditKind.Fix, Decision.Ignored),
            suggestion(closed, "new-kind", EditKind.Natural, Decision.Accepted),
            suggestion(closed, "new-decision", EditKind.Fix, Decision.Ignored),
            suggestion(closed, "old", EditKind.Fix, Decision.Superseded),
        ))
        raw { db ->
            db.execSQL("""UPDATE suggestions SET "kind" = 'style' WHERE "id" = 'new-kind'""")
            db.execSQL("""UPDATE suggestions SET "decision" = 'postponed' WHERE "id" = 'new-decision'""")
        }

        val summary = store.recent(10).first().single()
        assertEquals(1, summary.fixes)
        assertEquals(0, summary.rewordings)
        assertEquals(0, summary.taken)
        assertEquals(setOf("known", "old"), store.detail("s1")!!.suggestions.map { it.id }.toSet())
    }

    @Test
    fun aRecentSuccessfulCheckOfTheSameTextCanBeReused() = runBlocking<Unit> {
        val done = open("s1", 5_000).answered(5_500).closed(6_000)
        store.close(done, emptyList())
        assertEquals("s1", store.reusable(done.reuseKey, since = 4_000)?.session?.id)
        // Too old, other settings, other text, other language.
        assertNull(store.reusable(done.reuseKey, since = 5_001))
        assertNull(store.reusable(done.reuseKey.let { it.copy(settings = it.settings.copy(model = "other")) }, since = 4_000))
        assertNull(store.reusable(done.reuseKey.copy(textHash = "other"), since = 4_000))
        assertNull(store.reusable(done.reuseKey.copy(requestedLanguage = null), since = 4_000))
        assertNull(store.reusable(done.reuseKey.let { it.copy(settings = it.settings.copy(nativeLanguage = "Spanish")) }, since = 4_000))

        // Auto-detect matches auto-detect.
        val auto = open("s2", 5_000, model = "auto").copy(requestedLanguage = null).answered(5_500).closed(6_000)
        store.close(auto, emptyList())
        assertEquals("s2", store.reusable(auto.reuseKey, since = 4_000)?.session?.id)

        // Not one whose last attempt failed, nor one still open.
        val failed = open("s3", 5_000, model = "failing").answered(5_200).answered(5_500, verdict = null, failure = "Offline")
            .closed(6_000, Outcome.Failed)
        store.close(failed, emptyList())
        assertNull(store.reusable(failed.reuseKey, since = 4_000))
        val stillOpen = open("s4", 5_000, model = "open").answered(5_500)
        store.save(stillOpen)
        assertNull(store.reusable(stillOpen.reuseKey, since = 4_000))

        // Not one whose card ended on an answer made with other settings, though its latest
        // attempt matches: the re-check with the new model offered nothing to review.
        val changed = open("s5", 5_000, model = "before").answered(5_200).copy(model = "after").answered(5_500).closed(6_000)
        store.close(changed, listOf(suggestion(changed, "g5", EditKind.Fix, Decision.Ignored, attempt = 0)))
        assertEquals(0, store.detail("s5")!!.decidedAttempt())
        assertNull(store.reusable(changed.reuseKey, since = 4_000))
    }

    @Test
    fun reuseFindsAValidAnswerBehindManyRecentSessionsWithAnOlderDecidedAttempt() = runBlocking<Unit> {
        val valid = open("valid", 1_000, model = "after").answered(1_100).closed(1_200)
        store.close(valid, emptyList())
        repeat(25) { index ->
            val changed = open("changed-$index", 2_000L + index, model = "before").answered(3_000)
                .copy(model = "after").answered(4_000).closed(5_000)
            store.close(changed, listOf(suggestion(changed, "g-$index", EditKind.Fix, Decision.Ignored, attempt = 0)))
        }

        assertEquals("valid", store.reusable(valid.reuseKey, since = 1_000)?.session?.id)
    }

    @Test
    fun exportWritesOneLinePerSessionAsStored() = runBlocking<Unit> {
        val closed = open("s1", 5_000).answered(5_500).closed(6_000)
        store.close(closed, listOf(suggestion(closed, "g1", EditKind.Fix, Decision.Ignored)))
        store.save(open("s2", 7_000))
        // Written by a newer build: tokens this one doesn't know.
        raw { it.execSQL("""UPDATE sessions SET "origin" = 'widget', "outcome" = 'snoozed' WHERE "id" = 's2'""") }
        assertNull(store.detail("s2")!!.session.origin)

        val out = ByteArrayOutputStream()
        assertTrue(store.export(out))
        val lines = out.toString(Charsets.UTF_8.name()).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("\"kind\":\"fix\"") && lines[0].contains("\"decision\":\"ignored\""))
        assertTrue(lines[0], lines[0].contains("\"raw\":\"{\\\"status\\\":\\\"ok\\\"}\""))
        assertTrue(lines[1], lines[1].contains("\"origin\":\"widget\"") && lines[1].contains("\"outcome\":\"snoozed\""))
    }

    @Test
    fun clearingLeavesNoneOfTheTextInTheFiles() = runBlocking<Unit> {
        val secret = "Un secret ben guardat"
        store.close(open("s1", 5_000, text = secret).closed(6_000).copy(finalText = secret), emptyList())
        store.clear()
        for (file in listOf(context.getDatabasePath(NAME), context.getDatabasePath("$NAME-wal"))) {
            if (file.exists()) assertFalse(file.name, String(file.readBytes(), Charsets.ISO_8859_1).contains(secret))
        }
    }

    @Test
    fun aVersion1DatabaseIsUpgradedAndKeepsWorking() = runBlocking<Unit> {
        legacyDatabase(withStatus = false)
        val detail = store.detail("old")!!
        val session = detail.session
        assertEquals(Origin.Menu, session.origin)
        assertEquals(Outcome.Applied, session.outcome)
        assertEquals("moderate", session.punctuation)
        assertEquals("fix", session.judgments)
        assertEquals("gemini", session.provider)
        assertEquals("ca", session.requestedLanguage)
        assertEquals(SessionRecord.SCHEMA, session.schema)
        assertNull(session.nativeLanguage)
        assertEquals("""{"status":"ok"}""", session.attempts.single().raw)
        assertEquals(setOf(Decision.Accepted, Decision.Superseded, Decision.Ignored), detail.suggestions.map { it.decision }.toSet())

        // Recent counts the legacy rows like new ones.
        val summary = store.recent(10).first().single()
        assertEquals(1, summary.fixes)
        assertEquals(1, summary.rewordings)
        assertEquals(1, summary.taken)
        assertEquals(1, store.count().first())

        // An open one from the old build is abandoned; new writes work.
        store.markAbandoned(9_000)
        assertEquals(Outcome.Abandoned, store.detail("open")!!.session.outcome)
        val fresh = open("s1", 10_000).answered(10_500).closed(11_000)
        store.save(open("s1", 10_000))
        store.close(fresh, listOf(suggestion(fresh, "g9", EditKind.Fix, Decision.Accepted)))
        assertEquals(fresh, store.detail("s1")!!.session)
        assertEquals(3, store.count().first())
        assertTrue(store.export(ByteArrayOutputStream()))
    }

    @Test
    fun aVersion1DatabaseWithStatusIsUpgradedToo() = runBlocking<Unit> {
        legacyDatabase(withStatus = true)
        assertEquals(Verdict.Status.Ok, store.detail("old")!!.session.status)
        assertEquals(1, store.recent(10).first().single().taken)
    }

    @Test
    fun aVersion2DatabaseKeepsLanguageCodesAndFailureTokens() = runBlocking<Unit> {
        // Version 2 had this version's tables, with the language by name and failures by constant name.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            HistorySchema.CREATE.forEach(db::execSQL)
            val attempts = """[{"at":1500,"settled":[],"raw":null,"failure":"RateLimited","failureDetail":"slow down"}]"""
            db.execSQL(
                """INSERT INTO sessions ("id","deviceId","schema","createdAt","updatedAt","startedAt","closedAt","origin",
                "requestedLanguage","nativeLanguage","text","textHash","outcome","punctuation","judgments","provider","model",
                "promptHash","appVersion","attempts")
                VALUES ('v2','device',2,1000,2000,1000,2000,'menu','Spanish','English','Hola','h','failed','moderate','both',
                'openai','m','p1','0.2',?)""",
                arrayOf(attempts),
            )
            db.version = 2
        }
        val session = store.detail("v2")!!.session
        assertEquals("es", session.requestedLanguage)
        assertEquals("rate_limited", session.attempts.single().failure)
        assertEquals("slow down", session.attempts.single().failureDetail)
        assertEquals(SessionRecord.SCHEMA, session.schema)
    }

    @Test
    fun aVersion3DatabaseLosesTheTestersSessionsAndKeepsEachAttemptsLanguage() = runBlocking<Unit> {
        // Version 3 had this version's tables, and recorded the "Try it" card as origin 'tester'.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            HistorySchema.CREATE.forEach(db::execSQL)
            val attempts = """[{"at":1500,"settled":[],"raw":"{\"status\":\"ok\"}","failure":null,"failureDetail":null,"reusedFrom":null}]"""
            for ((id, origin, language) in listOf(Triple("menu", "menu", "'ca'"), Triple("tester", "tester", "'es'"), Triple("auto", "button", "NULL"))) {
                db.execSQL(
                    """INSERT INTO sessions ("id","deviceId","schema","createdAt","updatedAt","startedAt","closedAt","origin",
                    "requestedLanguage","nativeLanguage","text","textHash","outcome","punctuation","judgments","provider","model",
                    "promptHash","appVersion","attempts")
                    VALUES ('$id','device',3,1000,2000,1000,2000,'$origin',$language,'English','Com estas?','h','none','moderate',
                    'both','gemini','m','p1','0.3',?)""",
                    arrayOf(attempts),
                )
                db.execSQL(
                    """INSERT INTO suggestions VALUES ('g-$id','$id','device',3,2000,2000,NULL,0,'fix',4,9,'estas','estàs',NULL,'ignored',2000)""",
                )
            }
            db.version = 3
        }
        assertNull(store.detail("tester"))
        val menu = store.detail("menu")!!
        assertEquals(SessionRecord.SCHEMA, menu.session.schema)
        assertEquals("ca", menu.session.attempts.single().requestedLanguage)
        assertEquals("gemini", menu.session.attempts.single().provider)
        assertEquals(menu.session.reuseKey, menu.session.reuseKeyOf(0))
        assertEquals(1, menu.suggestions.size)
        assertNull(store.detail("auto")!!.session.attempts.single().requestedLanguage)
        assertEquals(2, store.count().first())
        // The tester's suggestions went with it.
        raw { db ->
            db.rawQuery("SELECT COUNT(*) FROM suggestions WHERE \"sessionId\" = 'tester'", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM suggestions", null).use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test
    fun aVersion1DatabaseLosesTheTestersSessionsToo() = runBlocking<Unit> {
        legacyDatabase(withStatus = true)
        raw { db ->
            db.execSQL(
                """INSERT INTO sessions ("id","deviceId","schema","createdAt","updatedAt","startedAt","closedAt","origin","text",
                "textHash","outcome","punctuation","judgments","provider","model","promptHash","appVersion","attempts")
                VALUES ('tried','device',1,4000,4000,4000,4000,'Tester','x','h3','None','Strict','Both','OpenAI','m','p1','0.1','[]')""",
            )
            db.execSQL(
                """INSERT INTO suggestions VALUES ('gt','tried','device',1,4000,4000,NULL,0,'fix',0,1,'x','y',NULL,'Ignored',4000)""",
            )
        }
        assertNull(store.detail("tried"))
        assertEquals("ca", store.detail("old")!!.session.attempts.single().requestedLanguage)
        raw { db ->
            db.rawQuery("SELECT COUNT(*) FROM suggestions WHERE \"sessionId\" = 'tried'", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun aVersionWithNoUpgradeStartsAfresh() = runBlocking<Unit> {
        // A version no migration knows: history starts over rather than staying off for good.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            db.execSQL("""CREATE TABLE sessions ("id" TEXT PRIMARY KEY, "something" TEXT)""")
            db.version = -1
        }
        val opened = open("s1", 5_000)
        store.save(opened)
        assertEquals(opened, store.detail("s1")!!.session)
    }

    /** Runs [block] on the database file directly, as another build would. */
    private fun raw(block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    /**
     * A database as the first history build (7237d59) made it: version 1, enums by constant
     * name, the answer under "verdict"; [withStatus] for the builds that added `status`
     * without changing the version.
     */
    private fun legacyDatabase(withStatus: Boolean) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            V1_CREATE.forEach(db::execSQL)
            if (withStatus) db.execSQL("""ALTER TABLE sessions ADD COLUMN "status" TEXT""")
            val attempts = """[{"at":1500,"settled":[],"verdict":"{\"status\":\"ok\"}","failure":null,"failureDetail":null}]"""
            db.execSQL(
                """INSERT INTO sessions ("id","deviceId","schema","createdAt","updatedAt","deletedAt","startedAt","closedAt",
                "origin","hostApp","requestedLanguage","text","textHash","finalText","outcome","punctuation","judgments",
                "provider","model","promptHash","appVersion","attempts","meaning")
                VALUES ('old','device',1,1000,2000,NULL,1000,2000,'Menu',NULL,'Catalan','Com estas?','h','Com estàs?',
                'Applied','Moderate','FixOnly','Gemini','m','p1','0.1',?,'How are you?')""",
                arrayOf(attempts),
            )
            db.execSQL(
                """INSERT INTO sessions ("id","deviceId","schema","createdAt","updatedAt","startedAt","origin","text",
                "textHash","punctuation","judgments","provider","model","promptHash","appVersion","attempts")
                VALUES ('open','device',1,3000,3000,3000,'Button','x','h2','Strict','Both','OpenAI','m','p1','0.1','[]')""",
            )
            if (withStatus) db.execSQL("""UPDATE sessions SET "status" = 'ok' WHERE "id" = 'old'""")
            listOf("fix" to "Accepted", "fix" to "Superseded", "natural" to "Ignored").forEachIndexed { i, (kind, decision) ->
                db.execSQL(
                    """INSERT INTO suggestions VALUES ('g$i','old','device',1,2000,2000,NULL,0,'$kind',4,9,'estas','estàs',NULL,'$decision',2000)""",
                )
            }
            db.version = 1
        }
    }

    private companion object {
        const val NAME = "history-test.db"

        /** The CREATE statements of version 1, as commit 7237d59 shipped them. */
        val V1_CREATE = listOf(
            """
            CREATE TABLE sessions (
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
                "meaning" TEXT
            )
            """,
            """
            CREATE TABLE suggestions (
                "id" TEXT PRIMARY KEY NOT NULL,
                "sessionId" TEXT NOT NULL REFERENCES sessions("id") ON DELETE CASCADE,
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
            """CREATE INDEX sessions_kept ON sessions("deletedAt", "startedAt")""",
            """CREATE INDEX sessions_text ON sessions("textHash", "startedAt")""",
            """CREATE INDEX suggestions_session ON suggestions("sessionId")""",
        ).map { it.trimIndent() }
    }
}
