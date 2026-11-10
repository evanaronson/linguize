package com.evanaronson.linguize.ui.words

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.DeckLibrary
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.words.DeckCoverage
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Real corpus/deck/CSV algorithms, with deterministic gates around replaceable asynchronous work. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WordsQueryOwnershipTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owner = ViewModelStore()
    private val morphology = Lemmatizer { word, _ -> setOf(Lexeme(word, Pos.Noun)) }

    @After fun clear() { owner.clear() }

    @Test fun aNonCooperativeOldLanguageCalculationCannotReplaceNewLanguageOrSelection() = runTest(main.dispatcher) {
        val gate = CalculationGate { words, _ -> words.language == "es" }
        val f = fixture(gate)
        advanceUntilIdle()
        assertTrue(gate.entered)

        f.vm.selectLanguage("ca")
        advanceUntilIdle()
        assertEquals("ca", f.vm.state.value.shownLanguage)
        assertEquals("ca", f.vm.state.value.coverage?.deck?.language)
        assertEquals(VocabSelection("ca", "bundled-ca"), f.store.selection())

        gate.release.complete(Unit)
        advanceUntilIdle()
        assertTrue("The old calculation really finished despite cancellation", gate.completed)
        assertEquals("ca", f.vm.state.value.shown?.language)
        assertEquals("bundled-ca", f.vm.state.value.coverage?.deck?.id)
        assertEquals(VocabSelection("ca", "bundled-ca"), f.store.selection())
    }

    @Test fun anOldDeckCalculationCannotReplaceTheLatestDeckOrDurableSelection() = runTest(main.dispatcher) {
        val gate = CalculationGate { _, deck -> deck.name == "A" }
        val f = fixture(gate)
        val a = f.store.import("A", "es", listOf(DeckEntry(1, "casa", "n")))
        val b = f.store.import("B", "es", listOf(DeckEntry(1, "perro", "n")))
        advanceUntilIdle()
        f.vm.selectDeck(a.id)
        advanceUntilIdle()
        assertTrue(gate.entered)
        assertNull(f.vm.state.value.coverage)

        f.vm.selectDeck(b.id)
        advanceUntilIdle()
        gate.release.complete(Unit)
        advanceUntilIdle()
        assertTrue(gate.completed)
        assertEquals(b.id, f.vm.state.value.deckId)
        assertEquals(b.id, f.vm.state.value.coverage?.deck?.id)
        assertEquals(b.id, f.store.selection().deckId)
    }

    @Test fun anOldLexemeCalculationCannotReplaceTheSelectedLemmaMeasurement() = runTest(main.dispatcher) {
        val gate = CalculationGate { _, deck -> deck.level == VocabLevel.Lexeme }
        val f = fixture(gate)
        advanceUntilIdle()
        assertTrue(gate.entered)
        f.vm.countBy(WordLevel.Lemmas)
        advanceUntilIdle()
        gate.release.complete(Unit)
        advanceUntilIdle()

        assertTrue(gate.completed)
        assertEquals(WordLevel.Lemmas, f.vm.state.value.shownLevel)
        assertEquals(VocabLevel.Lemma, f.vm.state.value.coverage?.deck?.level)
        assertEquals(VocabLevel.Lemma, f.store.selection().level)
    }

    @Test fun formsAndLexemesReuseCoverageWithoutChangingCsvOrSelection() = runTest(main.dispatcher) {
        val f = fixture()
        advanceUntilIdle()
        val coverage = f.vm.state.value.coverage
        assertNotNull(coverage)
        assertEquals(1, f.measurements.size)
        f.vm.exportCsv()
        advanceUntilIdle()
        val csv = (f.vm.export.value as WordsExport.Ready).file.readText()

        for (level in listOf(WordLevel.Forms, WordLevel.Lexemes)) {
            f.vm.countBy(level)
            advanceUntilIdle()
            assertEquals(level, f.vm.state.value.shownLevel)
            assertEquals(1, f.measurements.size)
            assertSame(coverage, f.vm.state.value.coverage)
            assertEquals(VocabSelection("es", "bundled-es"), f.store.selection())
            f.vm.exportCsv()
            advanceUntilIdle()
            assertEquals(csv, (f.vm.export.value as WordsExport.Ready).file.readText())
        }
    }

    @Test fun aNoPosDeckReusesCoverageAcrossLevelsWhileSelectionKeepsTheRequestedLevel() = runTest(main.dispatcher) {
        val f = fixture()
        advanceUntilIdle()
        val imported = f.store.import("No POS", "es", listOf(DeckEntry(1, "casa"), DeckEntry(2, "perro")))
        f.vm.selectDeck(imported.id)
        advanceUntilIdle()
        val coverage = f.vm.state.value.coverage
        assertEquals(VocabLevel.Lemma, coverage?.deck?.level)
        val builds = f.measurements.size

        for (level in listOf(WordLevel.Forms, WordLevel.Lemmas, WordLevel.Lexemes)) {
            f.vm.countBy(level)
            advanceUntilIdle()
            assertEquals(level, f.vm.state.value.shownLevel)
            assertEquals(builds, f.measurements.size)
            assertSame(coverage, f.vm.state.value.coverage)
            val savedLevel = if (level == WordLevel.Lemmas) VocabLevel.Lemma else VocabLevel.Lexeme
            assertEquals(VocabSelection("es", imported.id, savedLevel), f.store.selection())
        }
    }

    @Test fun aRealLemmaVariantRebuildsCoverageAndChangesCsvRanksAndSavedSelection() = runTest(main.dispatcher) {
        val f = fixture()
        advanceUntilIdle()
        val imported = f.store.import("Homographs", "es", listOf(
            DeckEntry(1, "casa", "v"), DeckEntry(2, "perro", "n"), DeckEntry(3, "casa", "n"),
        ))
        f.vm.selectDeck(imported.id)
        advanceUntilIdle()
        val builds = f.measurements.size
        assertEquals(setOf("casa/n"), f.vm.state.value.coverage?.used)
        f.vm.exportCsv()
        advanceUntilIdle()
        val csv = (f.vm.export.value as WordsExport.Ready).file.readText()
        assertTrue(csv.contains("casa,casa,n,3,1,0,100,"))

        f.vm.countBy(WordLevel.Lemmas)
        advanceUntilIdle()
        assertEquals(builds + 1, f.measurements.size)
        assertEquals(WordLevel.Lemmas, f.vm.state.value.shownLevel)
        assertEquals(VocabLevel.Lemma, f.vm.state.value.coverage?.deck?.level)
        assertEquals(setOf("casa"), f.vm.state.value.coverage?.used)
        assertEquals(VocabSelection("es", imported.id, VocabLevel.Lemma), f.store.selection())
        f.vm.exportCsv()
        advanceUntilIdle()
        assertEquals(csv.replace("casa,casa,n,3,", "casa,casa,n,1,"), (f.vm.export.value as WordsExport.Ready).file.readText())
    }

    @Test fun evidenceRevisionInvalidatesImmediatelyAndOldCalculationCannotPublishLater() = runTest(main.dispatcher) {
        val gate = CalculationGate { words, _ -> words.word("casa") != null }
        val f = fixture(gate)
        advanceUntilIdle()
        assertTrue(gate.entered)
        f.source.text = "perro"
        f.revisions.value++
        runCurrent()
        assertTrue(f.vm.state.value.measuring)
        assertFalse(f.vm.state.value.exportReady)

        advanceUntilIdle()
        gate.release.complete(Unit)
        advanceUntilIdle()
        assertTrue(gate.completed)
        assertEquals(listOf("perro"), f.vm.state.value.shown?.words?.map { it.form })
        assertEquals(setOf("perro/n"), f.vm.state.value.coverage?.used)
        assertTrue(f.vm.state.value.exportReady)
    }

    @Test fun obsoleteSelectionQueuedForIoIsRejectedAtTheStorageBoundaryWithoutCancellation() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val f = fixture(libraryIo = io)
        advanceUntilIdle()
        val current = AtomicBoolean(true)
        io.hold()
        val oldWrite = async { f.library.select(VocabSelection("ca", "bundled-ca")) { current.get() } }
        runCurrent()
        assertTrue(io.hasPendingWork)
        current.set(false)
        io.release()
        advanceUntilIdle()

        assertFalse(oldWrite.await())
        assertEquals(VocabSelection("es", "bundled-es"), f.store.selection())
    }

    @Test fun queuedExportCannotShareThePreviousLanguageAfterScopeChanges() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val f = fixture(vmIo = io)
        advanceUntilIdle()
        io.hold()
        f.vm.exportCsv()
        runCurrent()
        assertTrue(io.hasPendingWork)

        f.vm.selectLanguage("ca")
        advanceUntilIdle()
        io.release()
        advanceUntilIdle()
        assertNull(f.vm.export.value)
        f.vm.exportCsv()
        advanceUntilIdle()
        val result = f.vm.export.value as WordsExport.Ready
        assertTrue(f.vm.isExportCurrent(result))
        assertEquals("ca", result.language)
        assertTrue(result.file.readText().contains("gat,gat,n,"))
        assertFalse(result.file.readText().contains("casa"))
    }

    @Test fun queuedExportRetiredWithoutHistoryRevisionRefreshesEvidenceBeforeRetry() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val f = fixture(vmIo = io)
        advanceUntilIdle()
        io.hold()
        f.vm.exportCsv()
        runCurrent()
        assertTrue(io.hasPendingWork)
        f.source.text = "perro"
        f.exports.clear()
        io.release()
        advanceUntilIdle()

        assertNull(f.vm.export.value)
        assertEquals(listOf("perro"), f.vm.state.value.shown?.words?.map { it.form })
        f.vm.exportCsv()
        advanceUntilIdle()
        val result = f.vm.export.value as WordsExport.Ready
        assertTrue(result.file.readText().contains("perro,perro,n,"))
        assertFalse(result.file.readText().contains("casa"))
    }

    @Test fun exportClickOnRetiredSnapshotRefreshesAndASecondClickCanExport() = runTest(main.dispatcher) {
        val f = fixture()
        advanceUntilIdle()
        f.source.text = "perro"
        f.exports.clear()
        f.vm.exportCsv()
        advanceUntilIdle()
        assertNull(f.vm.export.value)
        assertEquals(listOf("perro"), f.vm.state.value.shown?.words?.map { it.form })

        f.vm.exportCsv()
        advanceUntilIdle()
        val result = f.vm.export.value as WordsExport.Ready
        assertTrue(f.vm.isExportCurrent(result))
        f.exports.invalidate()
        assertFalse("Retirement is rechecked immediately before sharing", f.vm.isExportCurrent(result))
    }

    @Test fun onlyNewestPickedFileCanOpenDialogAndCancelCannotResurrectIt() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val f = fixture(vmIo = io)
        advanceUntilIdle()
        io.hold()
        pick(f.vm, "a", "word\ncasa\n")
        runCurrent()
        pick(f.vm, "b", "word\nperro\n")
        runCurrent()
        io.release()
        advanceUntilIdle()
        assertEquals("word\nperro\n", f.vm.state.value.pendingImport?.text)

        f.vm.cancelImport()
        io.hold()
        pick(f.vm, "c", "word\ngat\n")
        runCurrent()
        assertTrue(io.hasPendingWork)
        f.vm.cancelImport()
        io.release()
        advanceUntilIdle()
        assertNull(f.vm.state.value.pendingImport)
        assertNull(f.vm.state.value.importMessage)
    }

    @Test fun acceptedImportSurvivesQueryReplacementButCannotStealNewSelection() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val f = fixture(libraryIo = io)
        advanceUntilIdle()
        pick(f.vm, "import", "word,pos\ncasa,n\n")
        advanceUntilIdle()
        assertNotNull(f.vm.state.value.pendingImport)
        io.hold()
        f.vm.confirmImport("Mine", "es")
        runCurrent()
        assertTrue(f.vm.state.value.importing)
        assertTrue(io.hasPendingWork)
        f.vm.selectLanguage("ca")
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertFalse(f.vm.state.value.importing)
        assertEquals("Mine", f.store.imported().single().name)
        assertEquals(f.store.imported(), f.vm.state.value.imported)
        assertEquals("ca", f.vm.state.value.shownLanguage)
        assertEquals("bundled-ca", f.vm.state.value.coverage?.deck?.id)
        assertEquals(VocabSelection("ca", "bundled-ca"), f.store.selection())
    }

    @Test fun initialLanguageApplicationRetainsItsCompatibleRememberedDeck() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        io.hold()
        val f = fixture(libraryIo = io)
        val remembered = f.store.import("Remembered", "es", listOf(DeckEntry(1, "casa", "n")))
        f.store.saveSelection(VocabSelection("es", remembered.id, VocabLevel.Lemma))
        advanceUntilIdle()
        assertTrue(io.hasPendingWork)
        f.vm.selectLanguage("es")
        f.vm.countBy(WordLevel.Lexemes)
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals("es", f.vm.state.value.shownLanguage)
        assertEquals(remembered.id, f.vm.state.value.coverage?.deck?.id)
        assertEquals(WordLevel.Lexemes, f.vm.state.value.shownLevel)
        assertEquals(VocabSelection("es", remembered.id), f.store.selection())
        f.vm.selectLanguage("ca")
        advanceUntilIdle()
        assertEquals("bundled-ca", f.vm.state.value.coverage?.deck?.id)
    }

    @Test fun enteringADetailPinsNullLanguageAndFormsBeforeRememberedSelectionArrives() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        io.hold()
        val f = fixture(libraryIo = io)
        f.store.saveSelection(VocabSelection("ca", "bundled-ca", VocabLevel.Lemma))
        advanceUntilIdle()
        assertTrue(io.hasPendingWork)

        f.vm.enter(null, WordLevel.Forms)
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertNull(f.vm.state.value.language)
        assertEquals(WordLevel.Forms, f.vm.state.value.level)
        assertEquals(WordLevel.Forms, f.vm.state.value.shownLevel)
        assertFalse(f.vm.state.value.measuring)
        assertTrue(f.vm.state.value.exportReady)
    }

    @Test fun enteringAScopedDetailPublishesOneMeasurementAndAutoListKeepsItsSelection() = runTest(main.dispatcher) {
        val f = fixture()
        advanceUntilIdle()
        val reads = f.source.reads

        f.vm.enter("ca", WordLevel.Lemmas)
        advanceUntilIdle()
        assertEquals(reads + 1, f.source.reads)
        assertEquals("ca", f.vm.state.value.shownLanguage)
        assertEquals(WordLevel.Lemmas, f.vm.state.value.shownLevel)
        assertEquals(VocabSelection("ca", "bundled-ca", VocabLevel.Lemma), f.store.selection())

        f.vm.enter(null)
        advanceUntilIdle()
        assertEquals(reads + 2, f.source.reads)
        assertEquals("ca", f.vm.state.value.shownLanguage)
        assertEquals(WordLevel.Lemmas, f.vm.state.value.shownLevel)
        assertEquals(VocabSelection("ca", "bundled-ca", VocabLevel.Lemma), f.store.selection())
    }

    @Test fun explicitDeckChosenWhileRestorationIsQueuedWinsOverRememberedDeck() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        io.hold()
        val f = fixture(libraryIo = io)
        val remembered = f.store.import("Remembered", "es", listOf(DeckEntry(1, "casa", "n")))
        val chosen = f.store.import("Chosen", "es", listOf(DeckEntry(1, "perro", "n")))
        f.store.saveSelection(VocabSelection("es", remembered.id))
        advanceUntilIdle()
        f.vm.selectLanguage("es")
        f.vm.selectDeck(chosen.id)
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(chosen.id, f.vm.state.value.coverage?.deck?.id)
        assertEquals(chosen.id, f.store.selection().deckId)
    }

    @Test fun laterSuccessfulDeletionCannotLoseAnEarlierUnacknowledgedCleanupId() = runTest(main.dispatcher) {
        val f = fixture()
        val a = f.store.import("A", "es", listOf(DeckEntry(1, "casa", "n")))
        val b = f.store.import("B", "es", listOf(DeckEntry(1, "perro", "n")))
        advanceUntilIdle()
        val blocked = File(folder.root, "vocab/decks/${a.id}.csv")
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        File(blocked, "keep").writeText("blocks physical cleanup")
        f.vm.deleteDeck(a.id)
        f.vm.deleteDeck(b.id)
        advanceUntilIdle()

        assertTrue(f.store.imported().isEmpty())
        assertTrue(f.vm.state.value.imported.isEmpty())
        assertEquals(WordsStorageFailure.DeleteDeck(a.id), f.vm.state.value.storageFailure)
        assertTrue(blocked.deleteRecursively())
        f.vm.retryStorage()
        advanceUntilIdle()
        assertNull(f.vm.state.value.storageFailure)
        assertFalse(blocked.exists())
    }

    private class Source : CorpusSource {
        override val name = "check"
        var text = "casa"
        var reads = 0
        override suspend fun utterances(): List<Utterance> {
            reads++
            return listOf(Utterance("es", name, "es", text, at = 1), Utterance("ca", name, "ca", "gat", at = 2))
        }
    }

    private data class Fixture(
        val vm: WordsViewModel, val source: Source, val revisions: MutableStateFlow<Long>,
        val store: VocabGapsStore, val library: DeckLibrary, val exports: Exports,
        val measurements: List<VocabDeck>,
    )

    private fun fixture(
        gate: CalculationGate? = null,
        vmIo: CoroutineDispatcher = main.dispatcher,
        libraryIo: CoroutineDispatcher = main.dispatcher,
    ): Fixture {
        val store = VocabGapsStore(File(folder.root, "vocab"))
        store.saveSelection(VocabSelection("es", "bundled-es"))
        val source = Source()
        val revisions = MutableStateFlow(0L)
        val library = DeckLibrary({ morphology }, store,
            BundledDecks { asset -> if (asset.contains("_ca")) "word\ngat\ngos\n" else "word,pos\ncasa,n\nperro,n\n" },
            io = libraryIo, compute = main.dispatcher)
        val exports = Exports(File(folder.root, "cache"))
        val measurements = mutableListOf<VocabDeck>()
        val vm = WordsViewModel(Corpus(listOf(source)), morphology, library, revisions,
            compute = main.dispatcher, io = vmIo, exports = exports,
            countCoverage = { words, deck ->
                gate?.awaitIfMatching(words, deck)
                measurements += deck
                DeckCoverage.build(words, deck, morphology)
            })
        owner.put("words", vm)
        return Fixture(vm, source, revisions, store, library, exports, measurements)
    }

    private fun pick(vm: WordsViewModel, name: String, text: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/$name.csv")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        vm.pick(context.contentResolver, uri, name)
    }

    /** Explicitly swallows query cancellation and returns its obsolete result to the caller. */
    private class CalculationGate(private val matches: (LanguageWords, VocabDeck) -> Boolean) {
        val release = CompletableDeferred<Unit>()
        var entered = false
        var completed = false
        suspend fun awaitIfMatching(words: LanguageWords, deck: VocabDeck) {
            if (entered || !matches(words, deck)) return
            entered = true
            withContext(NonCancellable) { release.await() }
            completed = true
        }
    }

    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPendingWork get() = pending.isNotEmpty()
        fun hold() { held = true }
        fun release() {
            held = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                delegate.dispatch(context, block)
            }
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) pending.addLast(context to block) else delegate.dispatch(context, block)
        }
    }
}
