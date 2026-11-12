package com.evanaronson.linguize.data

import android.app.Application
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Real filesystem failures, independent of OS permissions, sleeps, and mocked serialization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersistenceBoundaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedDraftWriteKeepsDurableAndCachedTextAndCanBeRetried() {
        val file = File(folder.root, WriteDraftStore.FILE_NAME)
        val store = WriteDraftStore(file)
        val original = WriteDraft("draft", "Bon dia", "ca", updatedAt = 10)
        val edited = original.copy(text = "Bon dia, veí", updatedAt = 20)
        store.saveCurrent(original)
        val obstruction = blockTemporaryFile(file)

        assertThrows(IOException::class.java) { store.saveCurrent(edited) }
        assertEquals(original, store.load().current)
        assertEquals(original, WriteDraftStore(file).load().current)

        assertTrue(obstruction.deleteRecursively())
        store.saveCurrent(edited)
        assertEquals(edited, store.load().current)
        assertEquals(edited, WriteDraftStore(file).load().current)
    }

    @Test fun importedDeckAndSelectionSurviveReopeningAndDeletion() {
        val dir = folder.newFolder("vocab")
        val store = VocabGapsStore(dir)
        val entries = listOf(DeckEntry(1, "casa", "n", "house", listOf("casas")))
        val imported = store.import("My Spanish", "es", entries, now = 10)
        val selected = VocabSelection("es", imported.id)
        store.saveSelection(selected)

        val reopened = VocabGapsStore(dir)
        assertEquals(listOf(imported), reopened.imported())
        assertEquals(entries, reopened.importedDeck(imported.id)?.entries)
        assertEquals(selected, reopened.selection())
        reopened.delete(imported.id)
        assertTrue(VocabGapsStore(dir).imported().isEmpty())
        assertEquals(null, VocabGapsStore(dir).importedDeck(imported.id))
    }

    @Test
    fun failedPhraseSaveMustNotPublishAnUndurablePhrase() {
        val file = File(folder.root, PhrasebookStore.FILE_NAME)
        val store = PhrasebookStore(file)
        val saved = requireNotNull(store.save(PhraseDraft("bon dia", language = "ca")))
        blockTemporaryFile(file)

        // An explicit failure is acceptable; a phantom saved phrase in the observable cache is not.
        runCatching { store.save(PhraseDraft("fins ara", language = "ca")) }

        assertEquals(listOf(saved), PhrasebookStore(file).load())
        assertEquals("Cache must describe the durable phrasebook after a failed save", listOf(saved), store.phrases.value)
    }

    @Test
    fun failedSelectionRenameMustBeReported() {
        val dir = folder.newFolder("vocab")
        // A nonempty destination directory makes both rename and fallback deletion fail.
        val destination = File(dir, "selection.json")
        assertTrue(destination.mkdir())
        File(destination, "keep").writeText("existing content")
        val selection = VocabSelection("ca", "bundled-ca")

        val attempt = runCatching { VocabGapsStore(dir).saveSelection(selection) }

        assertEquals("existing content", File(destination, "keep").readText())
        assertTrue("saveSelection must report failure when the destination cannot be replaced", attempt.isFailure)
    }

    private fun blockTemporaryFile(file: File): File = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("prevents cleanup from removing the obstruction")
    }
}
