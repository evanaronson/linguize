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
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Real IO completes through observable states; failures are fixed on disk before explicit retry. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WordsPersistenceTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owner = ViewModelStore()
    private val initialSelection = VocabSelection("es", "bundled-es")
    private val entries = listOf(DeckEntry(1, "casa"))
    private val dir get() = folder.root
    private val index get() = File(dir, "decks/index.json")
    private fun store() = VocabGapsStore(dir)

    @After fun clear() { owner.clear() }

    private fun words(): WordsViewModel {
        store().saveSelection(initialSelection)
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun utterances() = listOf(Utterance("1", name, "es", "una casa", at = 1))
        }
        val decks = DeckLibrary({ Lemmatizer.Identity }, store(), BundledDecks { "word\ncasa\nperro\n" })
        return WordsViewModel(Corpus(listOf(source)), Lemmatizer.Identity, decks, MutableStateFlow(0L)).also {
            owner.put("words", it)
        }
    }

    private suspend fun ready(words: WordsViewModel) = words.state.first {
        !it.loading && it.coverage != null && it.storageFailure == null
    }

    @Test fun failedSelectionSaveRemainsVisibleAndRetrySavesTheCurrentChoice() = runTest(main.dispatcher) {
        val own = store().import("Mine", "es", entries)
        val words = words()
        ready(words)
        val blocked = blockTemporaryFile(File(dir, "selection.json"))

        words.selectDeck(own.id)
        val failed = words.state.first { it.storageFailure == WordsStorageFailure.SaveSelection }
        assertEquals(own.id, failed.deckId)
        assertEquals(initialSelection, store().selection())

        assertTrue(blocked.deleteRecursively())
        words.retryStorage()
        words.state.first { it.storageFailure == null }
        assertEquals(own.id, store().selection().deckId)
    }

    @Test fun failedImportRetainsThePickedFileAndRetryPublishesOneDeck() = runTest(main.dispatcher) {
        val words = words()
        ready(words)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/words.csv")
        val text = "word\ncasa\nperro\n"
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        words.pick(context.contentResolver, uri, "Picked file")
        val picked = words.state.first { it.pendingImport != null || it.importMessage != null }
        assertNotNull(picked.pendingImport)
        val decksDir = requireNotNull(index.parentFile)
        assertTrue(decksDir.mkdirs())
        val blocked = blockTemporaryFile(index)

        words.confirmImport("My custom name", "es")
        val failed = words.state.first { !it.importing && it.importMessage == ImportMessage.SaveFailed }
        assertEquals(text, failed.pendingImport?.text)
        assertTrue(store().imported().isEmpty())
        assertTrue(decksDir.listFiles()!!.none { it.extension == "csv" })

        assertTrue(blocked.deleteRecursively())
        words.confirmImport("My custom name", "es")
        val saved = words.state.first { !it.importing && it.importMessage is ImportMessage.Imported }
        assertNull(saved.pendingImport)
        assertEquals("My custom name", store().imported().single().name)
        assertEquals(store().imported().single().id, saved.deckId)
        assertEquals(2, saved.coverage?.deck?.size)
    }

    @Test fun failedDeckDeletionKeepsItsRowAndCanBeRetried() = runTest(main.dispatcher) {
        val own = store().import("Mine", "es", entries)
        val words = words()
        ready(words)
        val blocked = blockTemporaryFile(index)

        words.deleteDeck(own.id)
        val failed = words.state.first { it.storageFailure == WordsStorageFailure.DeleteDeck(own.id) }
        assertEquals(listOf(own), failed.imported)
        assertEquals(listOf(own), store().imported())

        assertTrue(blocked.deleteRecursively())
        words.retryStorage()
        words.state.first { it.storageFailure == null && it.imported.none { deck -> deck.id == own.id } }
        assertTrue(store().imported().isEmpty())
        assertFalse(File(dir, "decks/${own.id}.csv").exists())
    }

    @Test fun anUnreadableIndexKeepsTheLastDeckListUntilRetrySucceeds() = runTest(main.dispatcher) {
        val own = store().import("Mine", "es", entries)
        val words = words()
        val before = ready(words)
        val savedIndex = index.readText()
        index.writeText("not an index")

        words.refresh()
        val failed = words.state.first { it.storageFailure == WordsStorageFailure.ReadDecks }
        assertEquals(before.decks, failed.decks)
        assertEquals(listOf(own), failed.imported)
        assertEquals(before.coverage, failed.coverage)
        assertEquals("not an index", index.readText())

        index.writeText(savedIndex)
        words.retryStorage()
        words.state.first { it.storageFailure == null }
        assertEquals(listOf(own), words.state.value.imported)
    }

    private fun blockTemporaryFile(file: File): File = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("blocks temporary writes")
    }
}
