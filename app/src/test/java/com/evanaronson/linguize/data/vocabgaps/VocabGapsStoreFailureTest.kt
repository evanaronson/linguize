package com.evanaronson.linguize.data.vocabgaps

import com.evanaronson.linguize.core.vocab.DeckEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class VocabGapsStoreFailureTest {
    @get:Rule val folder = TemporaryFolder()
    private val entries = listOf(DeckEntry(1, "casa", "n", "house", listOf("casas")))
    private val dir get() = folder.root
    private val index get() = File(dir, "decks/index.json")
    private val selection get() = File(dir, "selection.json")
    private fun store() = VocabGapsStore(dir)
    private fun words(id: String) = File(dir, "decks/$id.csv")

    @Test fun failedSelectionWritePreservesSavedValueAndRetrySurvivesReopen() {
        val before = VocabSelection("es", "bundled-es")
        val after = VocabSelection("ca", "bundled-ca")
        store().saveSelection(before)
        val blocked = blockTemporaryFile(selection)

        assertThrows(IOException::class.java) { store().saveSelection(after) }
        assertEquals(before, store().selection())
        assertTrue(blocked.deleteRecursively())
        store().saveSelection(after)
        assertEquals(after, store().selection())
    }

    @Test fun unreadableSelectionIsNotAnEmptyDefaultAndCannotBeOverwritten() {
        store().saveSelection(VocabSelection("es", "bundled-es"))
        val before = selection.readText()
        selection.writeText("{not json")

        assertThrows(IOException::class.java) { store().selection() }
        assertThrows(IOException::class.java) { store().saveSelection(VocabSelection("ca", "bundled-ca")) }
        assertEquals("{not json", selection.readText())

        selection.writeText(before)
        store().saveSelection(VocabSelection("ca", "bundled-ca"))
        assertEquals("ca", store().selection().language)
    }

    @Test fun unreadableIndexCannotDiscardExistingDecksOrPublishAnImport() {
        val existing = store().import("Existing", "es", entries)
        val before = index.readText()
        index.writeText("not an index")

        assertThrows(IOException::class.java) { store().imported() }
        assertThrows(IOException::class.java) { store().import("New", "es", entries) }
        assertThrows(IOException::class.java) { store().delete(existing.id) }
        assertEquals("not an index", index.readText())
        assertEquals(listOf("${existing.id}.csv"), File(dir, "decks").listFiles()!!.filter { it.extension == "csv" }.map { it.name })

        index.writeText(before)
        assertEquals(listOf(existing), store().imported())
        assertEquals(entries, store().importedDeck(existing.id)!!.entries)
    }

    @Test fun failedImportIndexWriteRemovesOnlyTheUnpublishedDeckAndCanRetry() {
        val existing = store().import("Existing", "es", entries)
        val blocked = blockTemporaryFile(index)

        assertThrows(IOException::class.java) { store().import("New", "es", entries) }
        assertEquals(listOf(existing), store().imported())
        assertEquals(listOf("${existing.id}.csv"), File(dir, "decks").listFiles()!!.filter { it.extension == "csv" }.map { it.name })
        assertEquals(entries, store().importedDeck(existing.id)!!.entries)

        assertTrue(blocked.deleteRecursively())
        val added = store().import("New", "es", entries)
        assertEquals(listOf(existing, added), store().imported())
        assertEquals(entries, store().importedDeck(added.id)!!.entries)
    }

    @Test fun failedDeleteIndexWriteKeepsTheDeckAndItsWordsForRetry() {
        val existing = store().import("Existing", "es", entries)
        val blocked = blockTemporaryFile(index)

        assertThrows(IOException::class.java) { store().delete(existing.id) }
        assertEquals(listOf(existing), store().imported())
        assertEquals(entries, store().importedDeck(existing.id)!!.entries)
        assertTrue(blocked.deleteRecursively())
        store().delete(existing.id)
        assertTrue(store().imported().isEmpty())
        assertFalse(words(existing.id).exists())
    }

    @Test fun failedPhysicalCleanupCanRetryAfterTheIndexCommits() {
        val existing = store().import("Existing", "es", entries)
        val path = words(existing.id)
        assertTrue(path.delete())
        assertTrue(path.mkdir())
        val obstruction = File(path, "keep").apply { writeText("blocks deletion") }

        assertThrows(IOException::class.java) { store().delete(existing.id) }
        assertTrue("Index membership is already committed", store().imported().isEmpty())
        assertTrue(obstruction.exists())
        assertTrue(obstruction.delete())
        store().delete(existing.id)
        assertFalse(path.exists())
        assertTrue(store().imported().isEmpty())
    }

    @Test fun referencedMissingOrMalformedWordsAreReadFailures() {
        val existing = store().import("Existing", "es", entries)
        val path = words(existing.id)
        val before = path.readText()
        assertTrue(path.delete())
        assertThrows(IOException::class.java) { store().importedDeck(existing.id) }
        path.writeText("")
        assertThrows(IOException::class.java) { store().importedDeck(existing.id) }
        path.writeText(before)
        assertEquals(entries, store().importedDeck(existing.id)!!.entries)
    }

    @Test fun aStorageParentWhichIsNotADirectoryIsNotAnEmptyStore() {
        val parent = folder.newFile("not-a-directory")
        val inaccessible = VocabGapsStore(parent)
        assertThrows(IOException::class.java) { inaccessible.imported() }
        assertThrows(IOException::class.java) { inaccessible.selection() }
        assertThrows(IOException::class.java) { inaccessible.import("New", "es", entries) }
        assertEquals("", parent.readText())
    }

    @Test fun concurrentStoreInstancesKeepEveryAcknowledgedImport() {
        val writers = 8
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(writers)
        val imports = (1..writers).map { number ->
            executor.submit<ImportedDeck> {
                ready.countDown()
                check(start.await(10, SECONDS))
                store().import("Deck $number", "es", entries)
            }
        }
        try {
            assertTrue(ready.await(10, SECONDS))
            start.countDown()
            val acknowledged = imports.map { it.get(10, SECONDS) }
            assertEquals(acknowledged.toSet(), store().imported().toSet())
            acknowledged.forEach { assertEquals(entries, store().importedDeck(it.id)!!.entries) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, SECONDS))
        }
    }

    private fun blockTemporaryFile(file: File): File = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("blocks temporary writes")
    }
}
