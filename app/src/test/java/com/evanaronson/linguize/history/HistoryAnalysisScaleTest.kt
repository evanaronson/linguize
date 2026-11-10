package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.history.corpus.CheckCorpusSource
import com.evanaronson.linguize.history.corpus.CheckUtterances
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SQLite, fixed evidence windows and counted reads; elapsed time is not an assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HistoryAnalysisScaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<String>()

    @After fun clean() { databases.forEach(context::deleteDatabase) }

    private data class Scale(
        val rows: Int, val visible: Int, val legacyPages: Int, val legacyDetails: Int,
    )

    @Test fun sharedReadsPreserveCorpusAndPatternsAtOneHundredToTenThousandStoredSessions() = runBlocking {
        // Six deliberately visible records per ten retained records. Hidden rows do not
        // consume a window, but corpus exclusions do.
        val scales = listOf(
            Scale(100, 60, 3, 120),
            Scale(1_000, 600, 14, 1_000),
            Scale(3_000, 1_800, 26, 2_200),
            Scale(10_000, 6_000, 38, 3_400),
        )
        for (scale in scales) {
            val fixtures = (0 until scale.rows).map(::fixture)
            val selects = Selects()
            val store = seed("analysis-scale-${scale.rows}.db", fixtures, selects::observe)
            store.page(null, 1) // Opening/schema setup is outside the measured reader phases.
            val visible = fixtures.filter { it.visible }.asReversed()
            assertEquals(scale.visible, visible.size)

            val old = CountedHistory(store)
            selects.start()
            val oldCorpusDetails = legacyRead(old, 3_000, 100)
            val oldPatternDetails = legacyRead(old, 400, 50)
            val legacySelects = selects.stop()
            val oldCorpus = CheckUtterances.from(oldCorpusDetails)
            val oldPatterns = PatternAnalyser.analyse(oldPatternDetails)
            assertEquals(scale.legacyPages, old.pages)
            assertEquals(scale.legacyDetails, old.details)
            assertEquals(0, old.batches)
            assertEquals(2 * scale.legacyDetails + scale.legacyPages, legacySelects)

            val counted = CountedHistory(store)
            val reader = HistoryEvidenceReader(counted)
            selects.start()
            val corpus = CheckCorpusSource(counted, evidence = reader).utterances()
            val patternsWindow = (reader.read(400) as HistoryEvidenceResult.Ready).snapshot
            val patterns = PatternAnalyser.analyse(patternsWindow.details)

            assertEquals(oldCorpus, corpus)
            assertEquals(oldPatterns, patterns)
            assertEquals(visible.take(3_000).asReversed().map { "check:${it.detail.session.id}" }, corpus.map { it.id })
            assertEquals(visible.take(400).map { it.detail.session.id }, patternsWindow.selectedIds)
            assertEquals(minOf(scale.visible, 400), patterns.checksRead)
            assertEquals(patterns.checksRead, patterns.checksCounted)
            assertEquals(0, patterns.reused)
            assertEquals(0, patterns.repeats)
            assertEquals(scale.visible > 400, patternsWindow.window.hasMore)
            assertEquals(400, patternsWindow.window.limit)

            val expectedCorpus = visible.take(3_000).associateBy { "check:${it.detail.session.id}" }
            for (utterance in corpus) {
                val row = expectedCorpus.getValue(utterance.id)
                assertEquals(row.detail.session.text, utterance.text)
                val expectedSpans = when (row.index % 10) {
                    0, 1, 2, 7 -> listOf(Span(0, "mot${row.index}".length))
                    9 -> listOf(Span(0, 4))
                    else -> emptyList()
                }
                assertEquals(utterance.id, expectedSpans, utterance.corrected)
            }

            assertEquals(1, counted.batches)
            assertEquals(0, counted.pages)
            assertEquals(0, counted.details)
            assertEquals(listOf(3_000), counted.limits)

            // Repeat consumers and language-specific projection reuse the same revision.
            assertEquals(corpus, CheckCorpusSource(counted, evidence = reader).utterances())
            val repeated = (reader.read(400) as HistoryEvidenceResult.Ready).snapshot
            assertEquals(patterns, PatternAnalyser.analyse(repeated.details))
            assertEquals(
                oldPatternDetails.filter { it.session.targetLanguage == "ca" }.map { it.session.id },
                repeated.details.filter { it.session.targetLanguage == "ca" }.map { it.session.id },
            )
            assertEquals(1, counted.batches)
            val retained = (reader.read(3_000) as HistoryEvidenceResult.Ready).snapshot.details
            val retainedTextBytes = retained.sumOf { detail ->
                val session = detail.session
                listOfNotNull(session.text, session.finalText).sumOf { it.toByteArray(StandardCharsets.UTF_8).size } +
                    session.attempts.sumOf { it.raw?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0 } +
                    detail.suggestions.sumOf { row ->
                        listOfNotNull(row.fromText, row.toText, row.why).sumOf { it.toByteArray(StandardCharsets.UTF_8).size }
                    }
            }
            val sharedSelects = selects.stop()
            assertEquals(2, sharedSelects)
            assertEquals(minOf(scale.visible, 3_000), retained.size)

            // Startup can request a smaller feature first. Measure that order too, rather
            // than implying every cold launch necessarily needs only one batch.
            val growing = CountedHistory(store)
            val growingReader = HistoryEvidenceReader(growing)
            selects.start()
            for (limit in listOf(400, 3_000)) {
                val snapshot = (growingReader.read(limit) as HistoryEvidenceResult.Ready).snapshot
                assertEquals(visible.take(limit).map { it.detail.session.id }, snapshot.selectedIds)
                assertEquals(scale.visible > limit, snapshot.window.hasMore)
            }
            val growingSelects = selects.stop()
            val expectedGrowingBatches = if (scale.visible <= 400) 1 else 2
            assertEquals(expectedGrowingBatches, growing.batches)
            assertEquals(expectedGrowingBatches * 2, growingSelects)
            println("HISTORY_READ_COUNTS stored=${scale.rows} visible=${scale.visible} " +
                "legacyPages=${old.pages} legacyDetails=${old.details} sharedBatches=${counted.batches} " +
                "sharedPages=${counted.pages} sharedDetails=${counted.details} legacySelects=$legacySelects " +
                "sharedSelects=$sharedSelects growingBatches=${growing.batches} growingSelects=$growingSelects " +
                "retained=${retained.size} retainedTextUtf8Bytes=$retainedTextBytes")
        }
    }

    @Test fun sharedWindowsKeepTheDifferentReuseDecisionOriginAndTieRulesOfTheirConsumers() = runBlocking {
        val original = "Vull anar perque plou."
        val corrected = "Vull anar perquè plou."
        val old = semantic("old", 10, original, corrected, changes = listOf(Change("perque", "perquè")))
        val reused = semantic("reuse", 20, original, corrected, reusedFrom = "old", changes = listOf(Change("perque", "perquè")))
        val recheck = semantic("recheck", 30, corrected, "Vull anar perquè plovia.", changes = listOf(Change("plou", "plovia")))
        val chat = semantic("chat", 40, corrected, corrected, origin = Origin.Chat)
        val decisions = semantic("decisions", 50, "dema cinc despres Te molt raó.", "dema cinc despres Tens tota la raó.", changes = listOf(
            Change("dema", "demà", Decision.Undone),
            Change("cinc", "5", Decision.Copied),
            Change("despres", "després", Decision.Ignored),
            Change("Te", "Té", Decision.Retired),
            Change("raó", "raons", Decision.Superseded),
            Change("Te molt raó", "Tens tota la raó", kind = EditKind.Natural),
        ))
        val tester = semantic("tester", 60, "Vull sortir dema.", "Vull sortir demà.", origin = Origin.Tester,
            changes = listOf(Change("dema", "demà")))
        val blank = semantic("blank", 70, "   ", " ")
        val hidden = semantic("hidden", 80, "Hidden unchanged menu input", "Hidden unchanged menu input", visible = false)
        val tieA = semantic("tie-a", 100, "Com estas?", "Com estàs?", changes = listOf(Change("estas", "estàs")))
        val tieZ = semantic("tie-z", 100, "Com estas?", "Com estàs?", changes = listOf(Change("estas", "estàs")))
        val store = seed("analysis-semantic-parity.db", listOf(old, reused, recheck, chat, decisions, tester, blank, hidden, tieA, tieZ))
        val counted = CountedHistory(store)
        val reader = HistoryEvidenceReader(counted)
        val corpusSource = CheckCorpusSource(counted, evidence = reader)

        val corpus = corpusSource.utterances()
        assertEquals(listOf("check:old", "check:chat", "check:decisions", "check:tie-a", "check:tie-z"), corpus.map { it.id })
        assertEquals(listOf(original, corrected, decisions.detail.session.text, "Com estas?", "Com estas?"), corpus.map { it.text })
        assertEquals(listOf(listOf(Span(10, 16)), emptyList(), listOf(Span(0, 4), Span(5, 9), Span(10, 17), Span(18, 20)), listOf(Span(4, 9)), listOf(Span(4, 9))),
            corpus.map { it.corrected })
        assertEquals(listOf("menu", "chat", "menu", "menu", "menu"), corpus.map { it.origin })

        val window = (reader.read(400) as HistoryEvidenceResult.Ready).snapshot
        assertEquals(listOf("tie-z", "tie-a", "blank", "tester", "decisions", "chat", "recheck", "reuse", "old"), window.selectedIds)
        val patterns = PatternAnalyser.analyse(window.details)
        assertEquals(9, patterns.checksRead)
        assertEquals(6, patterns.checksCounted)
        assertEquals(1, patterns.reused)
        assertEquals(2, patterns.repeats)
        assertEquals(listOf("tie-a", "blank", "tester", "decisions", "chat", "old"), patterns.sources.map { it.sessionId })
        assertEquals(1, counted.batches)
        assertEquals(0, counted.pages)
        assertEquals(0, counted.details)

        // Corpus reuse depends on whether its original is inside the visible window;
        // Patterns always excludes a reused answer.
        assertTrue(store.delete("old"))
        val after = corpusSource.utterances()
        assertEquals(listOf("check:reuse", "check:chat", "check:decisions", "check:tie-a", "check:tie-z"), after.map { it.id })
        val changed = PatternAnalyser.analyse((reader.read(400) as HistoryEvidenceResult.Ready).snapshot.details)
        assertEquals(5, changed.checksCounted)
        assertEquals(1, changed.reused)
        assertFalse(changed.sources.any { it.sessionId in setOf("old", "reuse") })
        assertEquals(2, counted.batches)
    }

    private data class Change(
        val from: String, val to: String, val decision: Decision = Decision.Accepted,
        val kind: EditKind = EditKind.Fix,
    )

    private fun semantic(
        id: String, at: Long, text: String, finalText: String,
        origin: Origin = Origin.Menu, reusedFrom: String? = null, visible: Boolean = true,
        changes: List<Change> = emptyList(),
    ): Fixture {
        val session = SessionRecord(
            id, "fixture", createdAt = at, updatedAt = at + 1, startedAt = at, closedAt = at + 1,
            origin = origin, requestedLanguage = "ca", text = text, textHash = SessionRecording.hash(text),
            finalText = finalText, outcome = Outcome.Applied, punctuation = "moderate", judgments = "both",
            provider = "gemini", model = "fixture", promptHash = "fixture", appVersion = "1",
            attempts = listOf(Attempt(at, raw = "{}", requestedLanguage = "ca", reusedFrom = reusedFrom)),
        )
        val suggestions = changes.mapIndexed { index, change ->
            val start = text.indexOf(change.from)
            check(start >= 0)
            SuggestionRecord(
                "$id-s$index", id, "fixture", createdAt = at, updatedAt = at + 1,
                attempt = 0, kind = change.kind, start = start, end = start + change.from.length,
                fromText = change.from, toText = change.to, decision = change.decision, decidedAt = at + 1,
            )
        }
        return Fixture(0, visible, SessionDetail(session, suggestions))
    }

    /** Frozen pre-batch page/detail protocol; the expected fixture IDs above independently pin its output. */
    private suspend fun legacyRead(store: HistoryStore, limit: Int, pageSize: Int): List<SessionDetail> {
        val ids = mutableListOf<String>()
        var cursor: HistoryCursor? = null
        do {
            val page = store.page(cursor, minOf(pageSize, limit - ids.size))!!
            ids += page.rows.map { it.id }
            cursor = page.next
        } while (cursor != null && ids.size < limit)
        return ids.distinct().mapNotNull { id ->
            when (val result = store.detail(id)) {
                is SessionDetailResult.Found -> result.detail
                SessionDetailResult.Missing -> null
                SessionDetailResult.Failed -> error("Fixture History read failed")
            }
        }
    }

    private data class Fixture(val index: Int, val visible: Boolean, val detail: SessionDetail)

    private fun fixture(index: Int): Fixture {
        val id = "check-${index.toString().padStart(5, '0')}"
        val category = index % 10
        val visible = category in setOf(0, 1, 2, 7, 8, 9)
        val from = if (category == 9) "esta" else "mot$index"
        val to = if (category == 9) "está" else "paraula$index"
        val text = "$from fila$index."
        val language = if (index % 2 == 0) "ca" else "es"
        val at = (index / 4).toLong() // Boundaries deliberately split equal-timestamp groups.
        val session = SessionRecord(
            id = id, deviceId = "fixture", createdAt = at, updatedAt = at + 1,
            startedAt = at, closedAt = if (category == 5) null else at + 1,
            deletedAt = if (category == 6) at + 2 else null,
            origin = if (category == 2) Origin.Chat else Origin.Menu,
            requestedLanguage = language, text = text, textHash = "unique-$index",
            finalText = if (category in setOf(2, 3)) text else "$to fila$index.",
            outcome = if (category == 4) Outcome.Abandoned else Outcome.Applied,
            punctuation = "moderate", judgments = "both", provider = "gemini", model = "fixture",
            promptHash = "fixture", appVersion = "1",
            attempts = listOf(Attempt(at, raw = "{}", requestedLanguage = language)),
        )
        val decision = when (category) {
            1, 2 -> Decision.Ignored
            7 -> Decision.Copied
            else -> Decision.Accepted
        }
        val fix = SuggestionRecord(
            id = "$id-fix", sessionId = id, deviceId = "fixture", createdAt = at, updatedAt = at + 1,
            attempt = 0, kind = if (category == 8) EditKind.Natural else EditKind.Fix,
            start = 0, end = from.length, fromText = from, toText = to,
            decision = decision, decidedAt = at + 1,
        )
        return Fixture(index, visible, SessionDetail(session, listOf(fix)))
    }

    private fun seed(name: String, fixtures: List<Fixture>, queryObserver: ((String) -> Unit)? = null): SqliteHistoryStore {
        databases += name
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            HistorySchema.CREATE.forEach(db::execSQL)
            db.beginTransaction()
            try {
                for (fixture in fixtures) {
                    db.execSQL(HistorySchema.SAVE_SESSION, fixture.detail.session.values().values.toTypedArray())
                    fixture.detail.suggestions.forEach {
                        db.execSQL(HistorySchema.INSERT_SUGGESTION, it.values().values.toTypedArray())
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = HistorySchema.VERSION
        }
        return SqliteHistoryStore(context, name, queryObserver = queryObserver)
    }

    private class Selects {
        private val count = AtomicInteger()
        @Volatile private var enabled = false
        private val select = Regex("\\bSELECT\\b", RegexOption.IGNORE_CASE)
        fun start() { count.set(0); enabled = true }
        fun stop(): Int { enabled = false; return count.get() }
        fun observe(sql: String) { if (enabled && select.containsMatchIn(sql)) count.incrementAndGet() }
    }

    private class CountedHistory(private val delegate: HistoryStore) : HistoryStore by delegate {
        var pages = 0
        var details = 0
        var batches = 0
        val limits = mutableListOf<Int>()

        override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? {
            pages++
            return delegate.page(before, limit)
        }

        override suspend fun detail(id: String): SessionDetailResult {
            details++
            return delegate.detail(id)
        }

        override suspend fun evidence(limit: Int): HistoryEvidenceResult {
            batches++
            limits += limit
            return delegate.evidence(limit)
        }
    }
}
