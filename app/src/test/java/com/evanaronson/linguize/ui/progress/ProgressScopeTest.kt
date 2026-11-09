package com.evanaronson.linguize.ui.progress

import com.evanaronson.linguize.PatternsLab
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeReport
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import com.evanaronson.linguize.ui.conjugations.ConjugationsState
import com.evanaronson.linguize.ui.phrasebook.inLanguage
import com.evanaronson.linguize.ui.practice.PracticeState
import com.evanaronson.linguize.ui.words.WordsState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/** Lab, Language scope: every Progress section shows only the app's current language. Fake data in Catalan and Spanish. */
class ProgressScopeTest {
    private fun item(id: String, language: String?) =
        PracticeItem(id, "check-$id", 1, language, "Nos vemos despues.", 10, 17, "después")

    @Test fun practiceCountsAndRunsOnlyTheCurrentLanguagesItems() {
        val items = listOf(item("a", "es"), item("b", "es"), item("c", "ca"), item("d", null))
        val all = PracticeState(items = items, now = 10, today = 0)
        assertEquals(4, all.due)
        assertEquals(2, all.copy(language = "es").due)
        assertEquals(1, all.copy(language = "ca").due)
        assertEquals(listOf("c"), all.copy(language = "ca").scoped!!.map { it.id })
        assertEquals(0, all.copy(language = "en").due)
    }

    private class Notes : ExplanationStore {
        var saved: SavedExplanation? = null
        override fun read() = saved
        override fun write(explanation: SavedExplanation) { saved = explanation }
        override fun delete() { saved = null }
    }

    private class Preferences : CheckPreferences {
        override val provider = Provider.Gemini
        override val punctuation = Punctuation.Moderate
        override val judgments = Judgments.Both
        override val nativeLanguage = "English"
        override fun model(provider: Provider): String? = "m"
    }

    @Test fun yourPatternsGroupsOnlyTheCurrentLanguagesFixes() = runBlocking {
        val history = FakePatternHistory(
            listOf(
                check("a", 1, fix("esta", "está"), text = "Mi casa esta lejos", language = "es"),
                check("b", 2, fix("tambien", "también"), text = "Yo tambien", language = "es"),
                check("c", 3, fix("lescola", "l'escola"), text = "Vaig a lescola", language = "ca"),
                check("d", 4, fix("lamic", "l'amic"), text = "Amb lamic", language = "ca"),
            ),
        )
        val notes = Notes()
        val lab = PatternsLab(history, notes, Preferences(), { null }, emptyMap(), { PatternsPrompt("system") }, { 1L })
        val spanish = lab.load("es")!!.report
        assertEquals(2, spanish.checksCounted)
        assertEquals(listOf(PatternKind.Accents), spanish.patterns.map { it.kind })
        val catalan = lab.load("ca")!!.report
        assertEquals(2, catalan.checksCounted)
        assertNull(catalan.pattern(PatternKind.Accents))
        assertEquals(4, lab.load()!!.report.checksCounted)

        // An explanation made for Spanish shows only there, and switching doesn't delete it.
        notes.saved = SavedExplanation(
            summary = "Accents.", tips = emptyList(), fingerprint = spanish.fingerprint, analyser = spanish.analyser,
            prompt = "p", provider = "gemini", model = "m", createdAt = 1, sources = spanish.evidenceSessions.toList(), checks = 2,
        )
        assertEquals("Accents.", lab.load("es")!!.explanation?.summary)
        assertNull(lab.load("ca")!!.explanation)
        assertEquals("Accents.", notes.saved?.summary)
    }

    @Test fun wordsAndConjugationsShowTheCurrentLanguageEvenWithNothingInItYet() {
        assertEquals("ca", WordsState(language = "ca", languages = listOf("es")).shownLanguage)
        assertEquals("es", WordsState(languages = listOf("es", "ca")).shownLanguage)
        assertEquals("ca", ConjugationsState(language = "ca").shown)
        assertNull(ConjugationsState(language = "ca").report)
    }

    @Test fun thePhrasebookShowsTheCurrentLanguagesPhrasesAndUnknownOnlyUnderAll() {
        val phrases = listOf(
            Phrase("1", "me va bien", language = "es", createdAt = 1),
            Phrase("2", "està", language = "ca", createdAt = 2),
            Phrase("3", "vale", language = null, createdAt = 3),
        )
        assertEquals(listOf("2"), inLanguage(phrases, "ca").map { it.id })
        assertEquals(listOf("1"), inLanguage(phrases, "es").map { it.id })
        assertEquals(3, inLanguage(phrases, null).size)
    }

    @Test fun timeSpentCountsOnlyTheCurrentLanguage() {
        val now = 1_800_000_000_000L
        val records = listOf(
            TimeRecord("a", "chat", "es", 60_000, 10_000, 20, 1, 0, now),
            TimeRecord("b", "chat", "ca", 30_000, 0, 10, 0, 0, now),
            TimeRecord("c", "menu", null, null, 5_000, 5, 0, 0, now),
        )
        val catalan = TimeReport.of(records, now, ZoneOffset.UTC, language = "ca")
        assertEquals(1, catalan.allTime.messages)
        assertEquals(30_000L, catalan.allTime.totalMs)
        assertEquals(listOf("ca"), catalan.languages.map { it.language })
        assertEquals(1, TimeReport.of(records, now, ZoneOffset.UTC, language = "es").thisWeek.messages)
        assertEquals(3, TimeReport.of(records, now, ZoneOffset.UTC).allTime.messages)
    }
}
