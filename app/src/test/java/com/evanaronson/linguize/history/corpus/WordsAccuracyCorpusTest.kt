package com.evanaronson.linguize.history.corpus

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.core.words.WordIndexCache
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words' right and wrong counts from saved checks, shaped as History stores them. */
class WordsAccuracyCorpusTest {
    private val ok = Attempt(at = 1, raw = "{}", requestedLanguage = "ca", targetLanguage = "ca")

    private fun session(id: String, text: String, startedAt: Long, attempts: List<Attempt> = listOf(ok)) = SessionRecord(
        id = id, deviceId = "device", createdAt = startedAt, updatedAt = startedAt, startedAt = startedAt,
        closedAt = startedAt, origin = Origin.Menu, requestedLanguage = "ca", text = text, textHash = SessionRecording.hash(text),
        finalText = "$text!", outcome = Outcome.Applied, punctuation = "moderate", judgments = "both",
        provider = "gemini", model = "model", promptHash = "prompt", appVersion = "1", attempts = attempts,
    )

    private fun fix(text: String, from: String, to: String, decision: Decision = Decision.Accepted) = SuggestionRecord(
        id = "$from>$to", sessionId = "s", deviceId = "device", createdAt = 1, updatedAt = 1, attempt = 0,
        kind = EditKind.Fix, start = text.indexOf(from), end = text.indexOf(from) + from.length, fromText = from, toText = to,
        decision = decision, decidedAt = 1,
    )

    private fun detail(session: SessionRecord, vararg suggestions: SuggestionRecord) =
        SessionDetail(session, suggestions.map { it.copy(sessionId = session.id) })

    private fun index(vararg details: SessionDetail) =
        WordIndex.build(CheckUtterances.from(details.toList()), Lemmatizer.Identity).language("ca")!!

    @Test
    fun aFixThatOnlyAddsACommaLeavesTheWordRight() {
        // The regression: "plou" to "plou," marked plou wrong, though the writer spelled it right.
        val text = "Avui plou molt"
        val words = index(detail(session("a", text, 1), fix(text, "plou", "plou,")))
        assertEquals(1, words.word("plou")!!.right)
        assertEquals(0, words.word("plou")!!.wrong)
    }

    @Test
    fun aFixOverSeveralWordsMarksOnlyTheWordsItChanged() {
        val text = "Hola com estas avui"
        val words = index(detail(session("a", text, 1), fix(text, "Hola com estas", "Hola, com estàs")))
        assertEquals(0, words.word("hola")!!.wrong)
        assertEquals(0, words.word("com")!!.wrong)
        assertEquals(1, words.word("estas")!!.wrong)
        // A missing word added between the writer's words changes none of them.
        val gap = "Vaig platja"
        val added = index(detail(session("b", gap, 1), fix(gap, "Vaig platja", "Vaig a la platja")))
        assertEquals(0, added.words.sumOf { it.wrong })
        // An elision fix: the article changed, the noun didn't.
        val elided = "el home"
        val elision = index(detail(session("c", elided, 1), fix(elided, "el home", "l'home")))
        assertEquals(1, elision.word("el")!!.wrong)
        assertEquals(0, elision.word("home")!!.wrong)
    }

    @Test
    fun anAccentOrCaseFixMarksTheWordWrong() {
        val text = "perque dilluns"
        val words = index(detail(session("a", text, 1), fix(text, "perque", "perquè"), fix(text, "dilluns", "Dilluns")))
        assertEquals(1, words.word("perque")!!.wrong)
        assertEquals(1, words.word("dilluns")!!.wrong)
    }

    @Test
    fun anUndoneFixStillCountsWrongAndAReusedAnswerDoesNotCountTwice() {
        val text = "Bon dia a tothom"
        val first = detail(session("a", text, 1), fix(text, "tothom", "tots", Decision.Undone))
        val reused = detail(session("b", text, 2, attempts = listOf(ok.copy(reusedFrom = "a"))))
        val words = index(first, reused)
        // One check's words, once: undo changes the sent text, not Linguize's judgment.
        assertEquals(0, words.word("tothom")!!.right)
        assertEquals(1, words.word("tothom")!!.wrong)
        assertEquals(4, words.totalWords)
    }

    @Test
    fun aChatMessageSentAsWrittenCountsItsFlaggedWordWrongWithoutCreditingTheReplacement() {
        val text = "Com estas avui"
        val sent = session("chat", text, 1).copy(origin = Origin.Chat, finalText = text)
        val words = index(detail(sent, fix(text, "estas", "estàs", Decision.Ignored)))
        assertEquals(0, words.word("estas")!!.right)
        assertEquals(1, words.word("estas")!!.wrong)
        assertEquals(0, words.word("estas")!!.accuracy)
        assertNull(words.word("estàs"))
        assertEquals(1, words.word("avui")!!.right)
        assertEquals(3, words.totalWords)
    }

    @Test
    fun deletingTheCheckWithTheWrongUseTakesItOutOfTheCounts() = runBlocking {
        val wrong = "Ahir plovia"
        val history = FakePatternHistory(listOf(
            detail(session("a", wrong, 1), fix(wrong, "plovia", "va ploure")),
            detail(session("b", "Ahir vaig sortir", 2)),
        ))
        val corpus = Corpus(listOf(CheckCorpusSource(history)))
        val cache = WordIndexCache(Lemmatizer.Identity)
        val before = cache.index(corpus.utterances()).language("ca")!!
        assertEquals(1, before.word("plovia")!!.wrong)
        assertEquals(2, before.word("ahir")!!.right)
        assertEquals(100, before.word("ahir")!!.accuracy)
        assertEquals(0, before.word("plovia")!!.accuracy)

        history.delete("a")
        val after = cache.index(corpus.utterances()).language("ca")!!
        assertNull(after.word("plovia"))
        assertEquals(1, after.word("ahir")!!.right)
        assertTrue(after.words.all { it.wrong == 0 })
    }
}
