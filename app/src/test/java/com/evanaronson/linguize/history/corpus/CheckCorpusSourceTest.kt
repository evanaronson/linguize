package com.evanaronson.linguize.history.corpus

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.words.WordIndexCache
import com.evanaronson.linguize.data.lab.NotTypedTexts
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckCorpusSourceTest {
    private val ok = Attempt(at = 1, raw = "{}", requestedLanguage = "ca", targetLanguage = "ca")

    private fun session(
        id: String,
        text: String,
        finalText: String = "$text.",
        startedAt: Long = 1,
        attempts: List<Attempt> = listOf(ok),
        origin: Origin = Origin.Menu,
    ) = SessionRecord(
        id = id, deviceId = "device", createdAt = startedAt, updatedAt = startedAt, startedAt = startedAt,
        closedAt = startedAt, origin = origin, requestedLanguage = "ca", text = text, textHash = SessionRecording.hash(text),
        finalText = finalText, outcome = Outcome.Applied, punctuation = "moderate", judgments = "both",
        provider = "gemini", model = "model", promptHash = "prompt", appVersion = "1", attempts = attempts,
    )

    private fun change(
        text: String,
        from: String,
        to: String,
        decision: Decision,
        kind: EditKind = EditKind.Fix,
        attempt: Int = 0,
        at: Int = text.indexOf(from),
    ) = SuggestionRecord(
        id = "$from>$to", sessionId = "s", deviceId = "device", createdAt = 1, updatedAt = 1, attempt = attempt,
        kind = kind, start = at, end = at + from.length, fromText = from, toText = to, decision = decision, decidedAt = 1,
    )

    private fun detail(session: SessionRecord, vararg suggestions: SuggestionRecord) =
        SessionDetail(session, suggestions.map { it.copy(sessionId = session.id) })

    private fun Span.of(text: String) = text.substring(start, end)

    @Test
    fun theOriginalTextIsTheUtteranceNotTheFinalText() {
        val text = "Vull anar a la platja perque fa sol"
        val u = CheckUtterances.of(detail(session("s1", text, finalText = "Vull anar a la platja perquè fa sol", startedAt = 42),
            change(text, "perque", "perquè", Decision.Accepted)))
        assertEquals("check:s1", u.id)
        assertEquals("check", u.source)
        assertEquals("ca", u.language)
        assertEquals(text, u.text)
        assertEquals(42L, u.at)
        assertEquals(listOf("perque"), u.corrected.map { it.of(text) })
    }

    @Test
    fun anAcceptedRewordingIsNotAMistake() {
        val text = "Em fa molta il·lusió anar-hi"
        val u = CheckUtterances.of(detail(session("s", text),
            change(text, "Em fa molta il·lusió", "Tinc moltes ganes", Decision.Accepted, EditKind.Natural)))
        assertTrue(u.corrected.isEmpty())
    }

    @Test
    fun undoneIgnoredAndCopiedFixesKeepTheWritersWords() {
        val text = "Ens veiem dema a les cinc o despres"
        val u = CheckUtterances.of(detail(session("s", text),
            change(text, "dema", "demà", Decision.Undone),
            change(text, "cinc", "5", Decision.Ignored),
            change(text, "despres", "després", Decision.Copied)))
        assertTrue(u.corrected.isEmpty())
    }

    @Test
    fun aFixTakenOverByAnAcceptedRewordingIsCorrected() {
        val text = "Te molt raó el que dius"
        val u = CheckUtterances.of(detail(session("s", text),
            change(text, "Te", "Té", Decision.Retired),
            change(text, "Te molt raó", "Tens tota la raó", Decision.Accepted, EditKind.Natural)))
        assertEquals(listOf("Te"), u.corrected.map { it.of(text) })
    }

    @Test
    fun aRetiredFixWithoutAnAcceptedRewordingIsNotCorrected() {
        val text = "Te molt raó"
        val u = CheckUtterances.of(detail(session("s", text),
            change(text, "Te", "Té", Decision.Retired),
            change(text, "Te molt raó", "Tens raó", Decision.Copied, EditKind.Natural)))
        assertTrue(u.corrected.isEmpty())
    }

    @Test
    fun supersededRevisionsDoNotCount() {
        val text = "Hola com estas"
        val attempts = listOf(ok, ok.copy(at = 2))
        val u = CheckUtterances.of(detail(session("s", text, attempts = attempts),
            change(text, "Hola", "Hola,", Decision.Superseded, attempt = 0),
            change(text, "estas", "estàs", Decision.Accepted, attempt = 1)))
        assertEquals(listOf("estas"), u.corrected.map { it.of(text) })
    }

    @Test
    fun aMisplacedSpanIsFoundByItsWords() {
        val text = "Bon dia, tinc una pregunta"
        val u = CheckUtterances.of(detail(session("s", text),
            change(text, "pregunta", "qüestió", Decision.Accepted, at = 3)))
        assertEquals(listOf("pregunta"), u.corrected.map { it.of(text) })
    }

    @Test
    fun theDetectedLanguageWinsOverTheRequestedOne() {
        val attempt = ok.copy(requestedLanguage = "es", targetLanguage = "es", detectedLanguage = "ca")
        assertEquals("ca", CheckUtterances.of(detail(session("s", "Bon dia", attempts = listOf(attempt)))).language)
    }

    @Test
    fun aReusedAnswerIsCountedOnce() {
        val first = session("a", "Bon dia a tothom", startedAt = 1)
        val again = session("b", "Bon dia a tothom", startedAt = 2, attempts = listOf(ok.copy(reusedFrom = "a")))
        val utterances = CheckUtterances.from(listOf(detail(again), detail(first)))
        assertEquals(listOf("check:a"), utterances.map { it.id })
        // Once the first is deleted, the reuse is the writer's only copy.
        assertEquals(listOf("check:b"), CheckUtterances.from(listOf(detail(again))).map { it.id })
    }

    @Test
    fun recheckingWhatWasPutBackIsNotTheWritersInput() {
        val first = session("a", "Com estas", finalText = "Com estàs?", startedAt = 1)
        val recheck = session("b", "Com estàs?", finalText = "Com estàs, Marta?", startedAt = 2)
        assertEquals(listOf("check:a"), CheckUtterances.from(listOf(detail(first), detail(recheck))).map { it.id })
    }

    @Test
    fun textLinguizeWroteIsLeftOut() {
        val filled = session("a", "Vull un gelat de maduixa")
        val typed = session("b", "Bon dia")
        val utterances = CheckUtterances.from(listOf(detail(filled), detail(typed)), notTyped = setOf(NotTypedTexts.hash(filled.text)))
        assertEquals(listOf("check:b"), utterances.map { it.id })
    }

    @Test
    fun notTypedHashMatchesHistorysTextHash() {
        val text = "Dóna-m'ho, si us plau 😊"
        assertEquals(SessionRecording.hash(text), NotTypedTexts.hash(text))
    }

    @Test
    fun sessionIdsRoundTrip() {
        assertEquals("s1", CheckUtterances.sessionOf(CheckUtterances.idOf("s1")))
        assertNull(CheckUtterances.sessionOf("chat:s1"))
    }

    @Test
    fun theSourceReadsHistoryAndADeletedCheckLeavesTheIndex() = runBlocking {
        val text = "Avui plou molt"
        val history = FakePatternHistory(listOf(
            detail(session("a", text, startedAt = 1), change(text, "plou", "plou,", Decision.Accepted)),
            detail(session("b", "Demà farà sol", startedAt = 2)),
        ))
        val corpus = Corpus(listOf(CheckCorpusSource(history)))
        val cache = WordIndexCache(Lemmatizer.Identity)
        val before = cache.index(corpus.utterances())
        assertEquals(1, before.language("ca")!!.word("plou")!!.tried)
        assertEquals(1, before.language("ca")!!.word("sol")!!.right)
        assertEquals(before, cache.index(corpus.utterances()))
        assertEquals(1, cache.builds)

        history.delete("b")
        val after = cache.index(corpus.utterances())
        assertEquals(2, cache.builds)
        assertNotEquals(before.fingerprint, after.fingerprint)
        assertNull(after.language("ca")!!.word("sol"))
    }

    @Test
    fun aFailedHistoryReadIsSkippedNotEmptied() = runBlocking {
        val history = FakePatternHistory(listOf(detail(session("a", "Hola"))))
        history.failPage = true
        // The corpus skips a failing source; the source itself must not report an empty History.
        val failed = runCatching { CheckCorpusSource(history).utterances() }
        assertTrue(failed.isFailure)
    }
}
