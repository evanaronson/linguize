package com.evanaronson.linguize.ui.words

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.DeckLibrary
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.CorpusRead
import com.evanaronson.linguize.core.corpus.CorpusReadStatus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.CorpusSourceCoverage
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WordsEvidenceFailureTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owner = ViewModelStore()
    private val source = Source()
    private val selection = VocabSelection("es", "bundled-es")
    private val store get() = VocabGapsStore(File(folder.root, "vocab"))
    private val exports by lazy { Exports(File(folder.root, "cache")) }

    @After fun clear() { owner.clear() }

    @Test fun initialReadFailureIsUnavailableAndRetryBuildsTheRealEvidence() = runTest(main.dispatcher) {
        source.throwIo = true
        val model = model()
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.evidenceFailed)
        assertEquals(CorpusReadStatus.Failed, model.state.value.evidenceStatus)
        assertNull(model.state.value.index)
        assertFalse(model.state.value.exportReady)
        assertEquals(selection, store.selection())
        model.exportCsv()
        advanceUntilIdle()
        assertNull(model.export.value)

        source.throwIo = false
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.evidenceFailed)
        assertEquals(CorpusReadStatus.Complete, model.state.value.evidenceStatus)
        assertEquals(listOf("casa"), model.state.value.shown?.words?.map { it.form })
        assertTrue(model.state.value.exportReady)
    }

    @Test fun partialEvidenceCannotReplaceTheIndexCoverageSelectionOrExport() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val before = model.state.value
        model.exportCsv()
        advanceUntilIdle()
        val shared = model.export.value as WordsExport.Ready
        assertTrue(exports.isCurrent(shared.file))

        source.status = CorpusReadStatus.Partial
        source.text = "perro"
        model.refresh()
        advanceUntilIdle()
        assertEquals(CorpusReadStatus.Partial, model.state.value.evidenceStatus)
        assertSame(before.index, model.state.value.index)
        assertSame(before.coverage, model.state.value.coverage)
        assertFalse(model.state.value.exportReady)
        assertNull(model.export.value)
        assertFalse(exports.isCurrent(shared.file))
        assertEquals(selection, store.selection())
        model.exportCsv()
        advanceUntilIdle()
        assertNull(model.export.value)

        source.status = CorpusReadStatus.Complete
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("perro"), model.state.value.shown?.words?.map { it.form })
        assertEquals(setOf("perro"), model.state.value.coverage?.used)
        model.exportCsv()
        advanceUntilIdle()
        val fresh = model.export.value as WordsExport.Ready
        assertTrue(fresh.file.readText().contains("perro"))
        assertFalse(fresh.file.readText().contains("casa"))
    }

    @Test fun successfulEmptyEvidenceAfterFailureReallyClearsTheOldIndex() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val before = model.state.value.index
        source.throwIo = true
        model.refresh()
        advanceUntilIdle()
        assertSame(before, model.state.value.index)
        assertTrue(model.state.value.evidenceFailed)

        source.throwIo = false
        source.text = ""
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.evidenceFailed)
        assertTrue(model.state.value.shown?.words.orEmpty().isEmpty())
        assertTrue(model.state.value.coverage?.used.orEmpty().isEmpty())
    }

    @Test fun completeBoundedReadRetainsItsWindowMetadataWithoutBeingTreatedAsFailed() = runTest(main.dispatcher) {
        source.coverage = CorpusSourceCoverage("check", CorpusReadStatus.Complete, 3000, true, 7L,
            selectedItems = 3000, loadedItems = 2999)
        val model = model()
        advanceUntilIdle()
        assertFalse(model.state.value.evidenceFailed)
        assertTrue(model.state.value.exportReady)
        assertEquals(listOf(source.coverage), model.state.value.evidenceSources)
        assertEquals(listOf("casa"), model.state.value.shown?.words?.map { it.form })
    }

    @Test fun unavailableEvidenceDoesNotHideCommittedDeckImportsDeletesOrCleanupRetries() = runTest(main.dispatcher) {
        val existing = store.import("Existing", "es", listOf(DeckEntry(1, "casa")))
        val model = model()
        advanceUntilIdle()
        source.throwIo = true
        model.refresh()
        advanceUntilIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/new.csv")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("word\nperro\n".toByteArray()))
        model.pick(context.contentResolver, uri, "New")
        advanceUntilIdle()
        model.confirmImport("New", "es")
        advanceUntilIdle()
        val added = store.imported().single { it.id != existing.id }
        assertEquals(store.imported(), model.state.value.imported)
        assertTrue(model.state.value.importMessage is ImportMessage.Imported)
        model.deleteDeck(added.id)
        advanceUntilIdle()
        assertEquals(listOf(existing), model.state.value.imported)

        val blocked = File(folder.root, "vocab/decks/${existing.id}.csv")
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        File(blocked, "keep").writeText("blocks physical cleanup")
        model.deleteDeck(existing.id)
        advanceUntilIdle()
        assertTrue(store.imported().isEmpty())
        assertTrue(model.state.value.imported.isEmpty())
        assertEquals(WordsStorageFailure.DeleteDeck(existing.id), model.state.value.storageFailure)
        assertTrue(model.state.value.evidenceFailed)
        assertFalse(model.state.value.exportReady)
        assertTrue(blocked.deleteRecursively())
        model.retryStorage()
        advanceUntilIdle()
        assertNull(model.state.value.storageFailure)
        assertTrue(model.state.value.evidenceFailed)
    }

    private fun model(): WordsViewModel {
        store.saveSelection(selection)
        val decks = DeckLibrary({ Lemmatizer.Identity }, store, BundledDecks { "word\ncasa\nperro\n" },
            io = main.dispatcher, compute = main.dispatcher)
        return WordsViewModel(Corpus(listOf(source)), Lemmatizer.Identity, decks, MutableStateFlow(0L),
            compute = main.dispatcher, io = main.dispatcher, exports = exports).also { owner.put("words", it) }
    }

    private class Source : CorpusSource {
        override val name = "check"
        var throwIo = false
        var text = "casa"
        var status = CorpusReadStatus.Complete
        var coverage = CorpusSourceCoverage(name, CorpusReadStatus.Complete)
        override suspend fun read(): CorpusRead {
            if (throwIo) throw IOException("Source unavailable")
            val items = if (text.isEmpty()) emptyList() else listOf(Utterance("check:1", name, "es", text, at = 1))
            return CorpusRead(items, listOf(coverage.copy(status = status)))
        }
        override suspend fun utterances() = read().requireComplete()
    }
}
