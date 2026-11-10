package com.evanaronson.linguize.ui.home

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.DeckLibrary
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.PatternsLab
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.progress.ProgressView
import com.evanaronson.linguize.ui.words.WordsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

/** Restore route values into new feature models and inspect the evidence they actually show. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScopedRouteReconstructionTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<ViewModelStore>()
    private val catalanSources = setOf("ca-1", "ca-2")
    private val spanishSources = setOf("es-1", "es-2", "es-3")
    private val history = FakePatternHistory(listOf(
        check("es-1", 1, fix("tambien", "también"), text = "tambien", language = "es"),
        check("es-2", 2, fix("aqui", "aquí"), text = "aqui", language = "es"),
        check("es-3", 3, fix("despues", "después"), text = "despues", language = "es"),
        check("ca-1", 4, fix("aixo", "això"), text = "aixo", language = "ca"),
        check("ca-2", 5, fix("cafe", "cafè"), text = "cafe", language = "ca"),
    ))
    private val utterances = listOf(
        Utterance("es-1", "check", "es", "casa perro", at = 1),
        Utterance("es-2", "check", "es", "casa", at = 2),
        Utterance("es-3", "check", "es", "perro", at = 3),
        Utterance("ca-1", "check", "ca", "gat", at = 4),
        Utterance("ca-2", "check", "ca", "gos", at = 5),
    )
    private val lemmas = Lemmatizer { word, _ -> setOf(Lexeme(word, Pos.Noun)) }

    @After fun clearModels() = owners.forEach { it.clear() }

    @Test fun autoCatalanPatternsSurvivesActivityReapplicationAndFreshModelRestoration() = runTest(main.dispatcher) {
        val path = block(ProgressView.Patterns)
        val first = patterns()
        first.model.setLanguage(path.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals(catalanSources, first.model.state.value.snapshot?.report?.evidenceSessions)

        val restored = Navigation.restore(path.saved)
        // Rotation reapplies the restored route to the retained model while global Auto is unchanged.
        first.model.setLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals("ca", first.model.state.value.language)
        assertEquals(catalanSources, first.model.state.value.snapshot?.report?.evidenceSessions)
        first.owner.clear()

        val fresh = patterns().model
        fresh.setLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals("ca", fresh.state.value.language)
        assertEquals(catalanSources, fresh.state.value.snapshot?.report?.evidenceSessions)
    }

    @Test fun autoCatalanPracticeRestoresItsDueDeckIntoAFreshModel() = runTest(main.dispatcher) {
        val path = block(ProgressView.Practice)
        val first = practice()
        first.model.setLanguage(path.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals(2, first.model.state.value.due)
        first.owner.clear()

        val restored = Navigation.restore(path.saved)
        val fresh = practice().model
        fresh.setLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals(catalanSources, fresh.state.value.scoped?.map { it.sessionId }?.toSet())
        fresh.start()
        assertNotNull(fresh.state.value.run?.current)
        assertEquals(setOf("ca"), fresh.state.value.run?.queue?.map { it.language }?.toSet())
        assertEquals(2, fresh.state.value.run?.size)
    }

    @Test fun autoCatalanWordsRestoresItsRouteDespiteADifferentRememberedSelection() = runTest(main.dispatcher) {
        val path = block(ProgressView.Words)
        vocab().saveSelection(VocabSelection("es", "bundled-es"))
        val first = words()
        first.model.selectLanguage(path.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals("ca", first.model.state.value.shownLanguage)
        assertEquals("bundled-ca", first.model.state.value.coverage?.deck?.id)
        first.owner.clear()
        // The selection file is independent state, not a substitute for the saved route.
        vocab().saveSelection(VocabSelection("es", "bundled-es"))

        val restored = Navigation.restore(path.saved)
        val fresh = words().model
        fresh.selectLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals(setOf("gat", "gos"), fresh.state.value.shown?.words?.map { it.form }?.toSet())
        assertEquals("ca", fresh.state.value.coverage?.deck?.language)
        assertEquals(VocabSelection("ca", "bundled-ca"), vocab().selection())
    }

    @Test fun autoCatalanConjugationsRestoresEvenWhenSpanishHasMoreEvidence() = runTest(main.dispatcher) {
        val path = block(ProgressView.Conjugations)
        val first = conjugations()
        first.model.refresh()
        advanceUntilIdle()
        assertEquals("es", first.model.state.value.shown)
        first.model.select(requireNotNull(path.current.effectiveLanguage(null)))
        assertEquals("ca", first.model.state.value.shown)
        first.owner.clear()

        val fresh = conjugations().model
        val restored = Navigation.restore(path.saved)
        fresh.select(requireNotNull(restored.current.effectiveLanguage(null)))
        fresh.refresh()
        advanceUntilIdle()
        assertEquals("ca", fresh.state.value.shown)
        assertEquals("ca", fresh.state.value.report?.language)
        assertEquals(2, fresh.state.value.report?.utterances)
        assertEquals(3, fresh.state.value.reports["es"]?.utterances)
    }

    @Test fun patternDetailInheritsItsBlockAndActualSelectorChangesUpdateTheWholeLivePath() = runTest(main.dispatcher) {
        val parent = block(ProgressView.Patterns)
        val path = parent.open(Screen.PatternDetail("accents").inScope(parent.current.scope))
        var restored = Navigation.restore(path.saved)
        val model = patterns().model
        model.setLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertEquals(catalanSources, model.state.value.snapshot?.report?.evidenceSessions)

        restored = restored.afterLanguageChange()
        model.setLanguage(restored.current.effectiveLanguage("es"))
        advanceUntilIdle()
        assertEquals(spanishSources, model.state.value.snapshot?.report?.evidenceSessions)
        assertEquals("es", restored.back().current.effectiveLanguage("es"))
        assertEquals(RouteScope.FollowApp, restored.current.scope)
        assertEquals(RouteScope.FollowApp, restored.back().current.scope)

        restored = Navigation.restore(restored.saved).afterLanguageChange()
        model.setLanguage(restored.current.effectiveLanguage(null))
        advanceUntilIdle()
        assertNull(model.state.value.language)
        assertEquals(spanishSources + catalanSources, model.state.value.snapshot?.report?.evidenceSessions)
    }

    @Test fun aWordDetailKeepsItsOwnLanguageAndLevelAfterAGlobalSwitchAndFreshRestoration() = runTest(main.dispatcher) {
        val path = block(ProgressView.Words).open(Screen.WordDetail("ca", "gat", "f"))
        val restored = Navigation.restore(path.afterLanguageChange().saved)
        assertEquals("es", restored.back().current.effectiveLanguage("es"))
        assertEquals("ca", restored.current.effectiveLanguage("es"))
        vocab().saveSelection(VocabSelection("es", "bundled-es", VocabLevel.Lemma))

        val fresh = words().model
        val detail = restored.current.destination as Screen.WordDetail
        // Detail route intent must be applied even when the fresh model happens to show Forms.
        fresh.selectLanguage(restored.current.effectiveLanguage("es"))
        fresh.countBy(requireNotNull(WordLevel.read(detail.level)))
        advanceUntilIdle()
        assertEquals("ca", fresh.state.value.shownLanguage)
        assertEquals(WordLevel.Forms, fresh.state.value.shownLevel)
        assertNotNull(fresh.state.value.shown?.entry(WordLevel.Forms, detail.key))
        assertEquals("ca", fresh.state.value.coverage?.deck?.language)
    }

    @Test fun conjugationCellAndVerbRoutesKeepTheirOwnLanguageWhenTheParentFollowsANewSelector() = runTest(main.dispatcher) {
        for (detail in listOf(Screen.ConjugationCell("ca", "present:1s"), Screen.ConjugationVerbs("ca", null))) {
            val restored = Navigation.restore(block(ProgressView.Conjugations).open(detail).afterLanguageChange().saved)
            val model = conjugations().model
            model.select(requireNotNull(restored.current.effectiveLanguage("es")))
            model.refresh()
            advanceUntilIdle()
            assertEquals("ca", model.state.value.report?.language)
            assertEquals(2, model.state.value.report?.utterances)
            assertEquals("es", restored.back().current.effectiveLanguage("es"))
        }
    }

    @Test fun legacyFeatureTokensStillFollowTheCurrentExplicitAppLanguage() = runTest(main.dispatcher) {
        val legacy = listOf("patterns", "practice", "words", "vocab_gaps", "conjugations")
        for (token in legacy) {
            val route = Navigation.restore(listOf("progress", token)).current
            assertEquals(RouteScope.FollowApp, route.scope)
            assertEquals("ca", route.effectiveLanguage("ca"))
            when (route.destination) {
                Screen.Patterns -> {
                    val model = patterns().model
                    model.setLanguage(route.effectiveLanguage("ca"))
                    advanceUntilIdle()
                    assertEquals(catalanSources, model.state.value.snapshot?.report?.evidenceSessions)
                }
                Screen.Practice -> {
                    val model = practice().model
                    model.setLanguage(route.effectiveLanguage("ca"))
                    advanceUntilIdle()
                    assertEquals(catalanSources, model.state.value.scoped?.map { it.sessionId }?.toSet())
                }
                Screen.Words -> {
                    vocab().saveSelection(VocabSelection("es", "bundled-es"))
                    val model = words().model
                    model.selectLanguage(route.effectiveLanguage("ca"))
                    advanceUntilIdle()
                    assertEquals("ca", model.state.value.shownLanguage)
                    assertEquals("ca", model.state.value.coverage?.deck?.language)
                }
                Screen.Conjugations -> {
                    val model = conjugations().model
                    model.select(requireNotNull(route.effectiveLanguage("ca")))
                    model.refresh()
                    advanceUntilIdle()
                    assertEquals("ca", model.state.value.report?.language)
                }
                else -> throw AssertionError("Unexpected legacy feature: $token")
            }
        }
    }

    @Test fun legacyAutoUsesAllLanguagesForPatternsAndPracticeAndExistingSingleLanguageDefaults() = runTest(main.dispatcher) {
        val patternRoute = requireNotNull(Screen.restore("patterns"))
        val patterns = patterns().model
        patterns.setLanguage(patternRoute.effectiveLanguage(null))
        val practiceRoute = requireNotNull(Screen.restore("practice"))
        val practice = practice().model
        practice.setLanguage(practiceRoute.effectiveLanguage(null))
        vocab().saveSelection(VocabSelection("es", "bundled-es"))
        val words = words().model
        requireNotNull(Screen.restore("vocab_gaps")).effectiveLanguage(null)?.let(words::selectLanguage)
        val conjugations = conjugations().model
        requireNotNull(Screen.restore("conjugations")).effectiveLanguage(null)?.let(conjugations::select)
        conjugations.refresh()
        advanceUntilIdle()

        assertEquals(spanishSources + catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        assertEquals(5, practice.state.value.due)
        assertEquals("es", words.state.value.shownLanguage)
        assertEquals("es", conjugations.state.value.shown)
    }

    @Test fun openingAProgressBlockUnderAnExplicitAppLanguageFollowsThatSelector() {
        for (view in listOf(ProgressView.Patterns, ProgressView.Practice, ProgressView.Words, ProgressView.Conjugations)) {
            val route = progressDestination(view, "es", "ca")
            assertEquals(RouteScope.FollowApp, route.scope)
            assertEquals("es", route.effectiveLanguage("es"))
            assertEquals("ca", requireNotNull(Screen.restore(route.saved)).effectiveLanguage("ca"))
        }
    }

    private fun block(view: ProgressView): Navigation = Navigation(Screen.Chat).open(Screen.Progress)
        .open(progressDestination(view, appLanguage = null, block = "ca"))

    private data class Owned<T : ViewModel>(val model: T, val owner: ViewModelStore)

    private fun <T : ViewModel> own(model: T): Owned<T> {
        val owner = ViewModelStore().also { owners += it }
        owner.put("feature", model)
        return Owned(model, owner)
    }

    private fun patterns(): Owned<PatternsViewModel> {
        val notes = object : ExplanationStore {
            override fun read(): SavedExplanation? = null
            override fun write(explanation: SavedExplanation) = Unit
            override fun delete() = Unit
        }
        val lab = PatternsLab(history, notes, FakePreferences(), { null }, emptyMap(),
            { PatternsPrompt("unused") }, io = main.dispatcher)
        return own(PatternsViewModel(lab, history.revisions()))
    }

    private fun practice() = own(PracticeViewModel(history,
        PracticeStore(File(folder.root, "practice.json"), main.dispatcher),
        clock = { 1_000_000_000_000L }, zone = { ZoneOffset.UTC }, savedState = SavedStateHandle()))

    private fun vocab() = VocabGapsStore(File(folder.root, "vocab"))

    private fun words(): Owned<WordsViewModel> {
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun utterances() = utterances
        }
        val decks = DeckLibrary({ lemmas }, vocab(),
            BundledDecks { asset -> if (asset.contains("_ca")) "word\ngat\ngos\n" else "word,pos\ncasa,n\nperro,n\n" },
            io = main.dispatcher, compute = main.dispatcher)
        return own(WordsViewModel(Corpus(listOf(source)), lemmas, decks, MutableStateFlow(0L),
            compute = main.dispatcher, io = main.dispatcher))
    }

    private fun conjugations() = own(ConjugationsViewModel({ utterances }, { Morphology() },
        { ZoneOffset.UTC }, main.dispatcher))
}
