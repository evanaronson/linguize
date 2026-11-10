package com.evanaronson.linguize.history.practice

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

class PracticeItemsTest {
    private fun session(id: String, text: String, startedAt: Long = 1, language: String? = "es", attempts: List<Attempt> = emptyList()) =
        SessionRecord(
            id = id, deviceId = "device", createdAt = startedAt, updatedAt = startedAt, startedAt = startedAt,
            closedAt = startedAt, origin = Origin.Menu, requestedLanguage = language, text = text, textHash = "hash",
            punctuation = "moderate", judgments = "both", provider = "gemini", model = "model",
            promptHash = "prompt", appVersion = "1", attempts = attempts,
        )

    private fun fix(
        id: String,
        text: String,
        from: String,
        to: String,
        decision: Decision = Decision.Accepted,
        kind: EditKind = EditKind.Fix,
        at: Int = text.indexOf(from),
        why: String? = "Reason for $id",
        attempt: Int = 0,
    ) = SuggestionRecord(
        id = id, sessionId = "s", deviceId = "device", createdAt = 1, updatedAt = 1, attempt = attempt,
        kind = kind, start = at, end = at + from.length, fromText = from, toText = to, why = why,
        decision = decision, decidedAt = 1,
    )

    private fun detail(session: SessionRecord, vararg suggestions: SuggestionRecord) =
        SessionDetail(session, suggestions.map { it.copy(sessionId = session.id) })

    @Test
    fun anAppliedFixBecomesItsSentence() {
        val text = "Nos vemos despues. Hasta luego"
        val item = PracticeItems.itemsOf(detail(session("c", text, startedAt = 42), fix("f", text, "despues", "después"))).single()
        assertEquals("f", item.id)
        assertEquals("c", item.sessionId)
        assertEquals(42L, item.checkedAt)
        assertEquals("es", item.language)
        assertEquals("Nos vemos despues.", item.sentence)
        assertEquals("despues", item.wrong)
        assertEquals("después", item.right)
        assertEquals("Nos vemos después.", item.corrected)
        assertEquals("Reason for f", item.why)
    }

    @Test
    fun theRightSentenceIsChosen() {
        val text = "Hola. Com estas? Bé."
        val item = PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "estas", "estàs"))).single()
        assertEquals("Com estas?", item.sentence)
        assertEquals("Com estàs?", item.corrected)
    }

    @Test
    fun otherAppliedFixesInTheSentenceAreAlreadyMade() {
        val text = "Ayer fui a la playa con mis amigo y comi paella."
        val items = PracticeItems.itemsOf(detail(session("c", text),
            fix("plural", text, "amigo", "amigos"),
            fix("accent", text, "comi", "comí"),
        )).associateBy { it.id }
        val accent = items.getValue("accent")
        assertEquals("Ayer fui a la playa con mis amigos y comi paella.", accent.sentence)
        assertEquals("comi", accent.wrong)
        assertEquals("Ayer fui a la playa con mis amigos y comí paella.", accent.corrected)
        val plural = items.getValue("plural")
        assertEquals("Ayer fui a la playa con mis amigo y comí paella.", plural.sentence)
        assertEquals("amigo", plural.wrong)
    }

    @Test
    fun onlyAppliedFixesBecomeItems() {
        val text = "uno dos tres cuatro cinco seis siete ocho"
        val rows = listOf(
            fix("accepted", text, "uno", "Uno"),
            fix("undone", text, "dos", "doss", Decision.Undone),
            fix("ignored", text, "tres", "tress", Decision.Ignored),
            fix("superseded", text, "cuatro", "cuatros", Decision.Superseded),
            fix("copied", text, "cinco", "cincos", Decision.Copied),
            fix("retired", text, "seis", "seiss", Decision.Retired),
            fix("rewording", text, "siete", "sietes", kind = EditKind.Natural),
            fix("bad-span", text, "ocho", "ochos", at = 0),
            fix("no-change", text, "ocho", "ocho"),
        )
        val items = PracticeItems.itemsOf(SessionDetail(session("c", text), rows))
        assertEquals(listOf("accepted"), items.map { it.id })
    }

    @Test
    fun anAppliedRewordingIsContextButNotAnItem() {
        val text = "Te escribo para decir que llego tarde, perdon."
        val items = PracticeItems.itemsOf(detail(session("c", text),
            fix("rewording", text, "para decir", "para avisarte", kind = EditKind.Natural),
            fix("accent", text, "perdon", "perdón"),
        ))
        val item = items.single()
        assertEquals("Te escribo para avisarte que llego tarde, perdon.", item.sentence)
        assertEquals("perdón", item.right)
    }

    @Test
    fun aFixInsideAWordIsWidenedToTheWord() {
        val text = "Nos vemos despues."
        val item = PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "e", "é", at = text.indexOf("es.")))).single()
        assertEquals("despues", item.wrong)
        assertEquals("después", item.right)
    }

    @Test
    fun aBareInsertionTakesTheNextWord() {
        val text = "Voy casa."
        val item = PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "", "a ", at = 4))).single()
        assertEquals("casa", item.wrong)
        assertEquals("a casa", item.right)
        assertEquals("Voy a casa.", item.corrected)
        assertEquals(PracticeItem.Mode.Type, item.mode)
    }

    @Test
    fun anInsertionAtTheEndTakesThePreviousWord() {
        val text = "Muchas gracias"
        val item = PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "", "!", at = text.length))).single()
        assertEquals("gracias", item.wrong)
        assertEquals("gracias!", item.right)
        assertEquals(PracticeItem.Mode.Choose, item.mode)
    }

    @Test
    fun aLongSentenceIsCutAroundTheFix() {
        val words = (1..80).joinToString(" ") { "palabra$it" }
        val text = "$words despues $words"
        val item = PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "despues", "después"))).single()
        assertTrue(item.sentence.length <= PracticeItems.MAX_SENTENCE)
        assertEquals("despues", item.wrong)
        assertTrue(item.sentence.startsWith("palabra"))
    }

    @Test
    fun theLanguageIsTheFixesOwnAttempts() {
        val text = "Com estas?"
        val attempts = listOf(Attempt(1, targetLanguage = "es"), Attempt(2, targetLanguage = "ca"))
        val item = PracticeItems.itemsOf(detail(session("c", text, language = null, attempts = attempts),
            fix("f", text, "estas", "estàs", attempt = 1))).single()
        assertEquals("ca", item.language)
    }

    @Test
    fun aMissingReasonIsNull() {
        val text = "Com estas?"
        assertNull(PracticeItems.itemsOf(detail(session("c", text), fix("f", text, "estas", "estàs", why = " "))).single().why)
    }

    @Test
    fun repeatsBecomeOneItemKeepingTheOldestOrOneWithState() {
        val a = "Nos vemos despues."
        val b = "Despues te llamo."
        val details = listOf(
            detail(session("new", b, startedAt = 3), fix("n", b, "Despues", "Después")),
            detail(session("old", a, startedAt = 1), fix("o", a, "despues", "después")),
            detail(session("mid", a, startedAt = 2), fix("m", a, "despues", "después")),
            detail(session("ca", a, startedAt = 4, language = "ca"), fix("c", a, "despues", "después")),
        )
        assertEquals(listOf("c", "o"), PracticeItems.from(details).map { it.id })
        assertEquals(listOf("c", "m"), PracticeItems.from(details, preferred = setOf("m")).map { it.id })
        assertEquals(listOf("c", "m"), PracticeItems.from(details, preferred = setOf("m", "n")).map { it.id })
        assertEquals(listOf("c", "o"), PracticeItems.from(details, preferred = setOf("missing")).map { it.id })
    }

    @Test
    fun sourceReadsEveryPageAndSkipsMissingChecks() = runBlocking {
        val texts = (1..120).associate { "c$it" to "Nos vemos despues $it." }
        val store = FakeStore(texts.mapValues { (id, text) ->
            if (id == "c7") SessionDetailResult.Missing
            else SessionDetailResult.Found(detail(session(id, text, startedAt = id.drop(1).toLong()),
                fix("f-$id", text, "despues ${id.drop(1)}", "después ${id.drop(1)}"))) as SessionDetailResult
        })
        val items = PracticeSource(store).load()!!
        assertEquals(119, items.size)
        assertTrue(items.none { it.sessionId == "c7" })
    }

    @Test
    fun aFailedReadIsNotAnEmptyDeck() = runBlocking {
        val text = "Nos vemos despues."
        val found = SessionDetailResult.Found(detail(session("a", text), fix("f", text, "despues", "después")))
        assertNull(PracticeSource(FakeStore(mapOf("a" to found, "b" to SessionDetailResult.Failed))).load())
        assertNull(PracticeSource(FakeStore(mapOf("a" to found), pageFails = true)).load())
    }

    /** History with only what practice reads: pages, newest first, and details. */
    private class FakeStore(
        private val details: Map<String, SessionDetailResult>,
        private val pageFails: Boolean = false,
    ) : HistoryStore {
        private val order = details.keys.toList()

        override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? {
            if (pageFails) return null
            val from = before?.let { order.indexOf(it.id) + 1 } ?: 0
            val rows = order.drop(from).take(limit).map {
                SessionSummary(it, 0, Origin.Menu, null, "", null, 0, 0, 0)
            }
            val next = rows.lastOrNull()?.takeIf { from + rows.size < order.size }?.let { HistoryCursor(0, it.id) }
            return HistoryPage(rows, next)
        }

        override suspend fun detail(id: String) = details[id] ?: SessionDetailResult.Missing
        override suspend fun save(session: SessionRecord) = error("Practice must not write History")
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = error("Practice must not write History")
        override suspend fun markAbandoned(now: Long) = error("unused")
        override fun count(): Flow<Int> = flowOf(details.size)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun revisions(): Flow<Long> = flowOf(0)
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun delete(id: String) = error("Practice must not delete History")
        override suspend fun clear() = error("Practice must not delete History")
        override suspend fun export(out: OutputStream) = error("unused")
    }
}
