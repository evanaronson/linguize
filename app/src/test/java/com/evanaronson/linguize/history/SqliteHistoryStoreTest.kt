package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** The store on a real SQLite, as Robolectric runs it. A plain Application, so the app's own start-up doesn't touch the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SqliteHistoryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SqliteHistoryStore(context, NAME)

    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    private fun open(id: String, startedAt: Long, text: String = "Com estas?", model: String = "m") = SessionRecord(
        id = id,
        deviceId = "device",
        createdAt = startedAt,
        updatedAt = startedAt,
        startedAt = startedAt,
        origin = Origin.Menu,
        requestedLanguage = "ca",
        nativeLanguage = "English",
        text = text,
        textHash = SessionRecording.hash(text),
        punctuation = "moderate",
        judgments = "both",
        provider = "gemini",
        model = model,
        promptHash = "p1",
        appVersion = "1.0",
    )

    /** An attempt that ran with the session's language and settings, as `SessionRecording` records it. */
    private fun SessionRecord.answered(at: Long, verdict: String? = """{"status":"ok"}""", failure: String? = null) = copy(
        updatedAt = at,
        attempts = attempts + Attempt(
            at, raw = verdict, failure = failure, requestedLanguage = requestedLanguage, nativeLanguage = nativeLanguage,
            punctuation = punctuation, judgments = judgments, provider = provider, model = model, promptHash = promptHash,
        ),
        status = Verdict.Status.Ok.takeIf { failure == null },
    )

    private fun SessionRecord.closed(at: Long, outcome: Outcome = Outcome.None) = copy(updatedAt = at, closedAt = at, outcome = outcome)

    private fun suggestion(session: SessionRecord, id: String, kind: EditKind, decision: Decision, attempt: Int = 0) = SuggestionRecord(
        id = id,
        sessionId = session.id,
        deviceId = "device",
        createdAt = session.updatedAt,
        updatedAt = session.updatedAt,
        attempt = attempt,
        kind = kind,
        start = 4,
        end = 9,
        fromText = "estas",
        toText = "estàs",
        why = "Accent",
        decision = decision,
        decidedAt = session.updatedAt,
    )

    @Test
    fun aSessionIsSavedOpenAndThenClosedWithItsSuggestions() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        assertEquals(opened, store.detail("s1")?.session)
        // Open sessions aren't in Recent, nor counted: the count is what Recent can show.
        assertTrue(store.recent(10).first().isEmpty())
        assertEquals(0, store.count().first())
        assertNull(store.since().first())

        val closed = opened.answered(6_000).closed(7_000, Outcome.Applied).copy(finalText = "Com estàs?")
        val suggestions = listOf(
            suggestion(closed, "g1", EditKind.Fix, Decision.Accepted),
            suggestion(closed, "g2", EditKind.Natural, Decision.Ignored),
            suggestion(closed, "g0", EditKind.Fix, Decision.Superseded),
        )
        store.close(closed, suggestions)
        val detail = store.detail("s1")!!
        assertEquals(closed, detail.session)
        assertEquals(suggestions.toSet(), detail.suggestions.toSet())

        assertEquals(1, store.count().first())
        assertEquals(5_000L, store.since().first())
        val summary = store.recent(10).first().single()
        assertEquals("s1", summary.id)
        assertEquals(Outcome.Applied, summary.outcome)
        assertEquals(Verdict.Status.Ok, summary.status)
        assertEquals(1, summary.fixes)
        assertEquals(1, summary.rewordings)
        assertEquals(1, summary.taken)

        // Closing again replaces the suggestions rather than adding to them.
        store.close(closed, suggestions.take(1))
        assertEquals(1, store.detail("s1")!!.suggestions.size)
    }

    @Test
    fun aClosedSessionIsntChangedByALateSave() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        store.close(opened.closed(6_000), emptyList())
        store.save(opened.answered(6_500))
        assertEquals(6_000L, store.detail("s1")!!.session.closedAt)
        assertTrue(store.detail("s1")!!.session.attempts.isEmpty())
    }

    @Test
    fun clearingWhileACardIsOpenKeepsItsSessionGone() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        store.clear()
        assertEquals(0, store.count().first())

        store.save(opened.answered(9_000))
        store.close(opened.answered(9_000).closed(9_500), listOf(suggestion(opened, "g1", EditKind.Fix, Decision.Accepted)))
        assertNull(store.detail("s1"))
        assertEquals(0, store.count().first())

        // A check started after the clear is recorded as usual, even with a clock set back since.
        val later = open("s2", 1_000)
        store.save(later)
        assertNotNull(store.detail("s2"))
        store.close(later.closed(1_500), emptyList())
        assertEquals(1, store.count().first())
    }

    @Test
    fun deletingAnOpenSessionKeepsItGone() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        store.save(open("s2", 5_100))
        store.delete("s1")
        store.close(opened.answered(6_000).closed(7_000), emptyList())
        assertNull(store.detail("s1"))
        assertNotNull(store.detail("s2"))
    }

    @Test
    fun sessionsLeftOpenByAnEarlierProcessAreAbandoned() = runBlocking<Unit> {
        // The earlier process opened "old" at a time later than "new" by the clock: only who opened it counts.
        SqliteHistoryStore(context, NAME).save(open("old", 50_000))
        store.save(open("new", 20_000))
        store.markAbandoned(30_000)
        val old = store.detail("old")!!.session
        assertEquals(Outcome.Abandoned, old.outcome)
        assertEquals(30_000L, old.closedAt)
        assertNull(store.detail("new")!!.session.closedAt)
        assertEquals(listOf("old"), store.recent(10).first().map { it.id })
    }

    @Test
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
