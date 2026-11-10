package com.evanaronson.linguize.data.memory

import com.evanaronson.linguize.core.memory.FactCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Durable acknowledgement and retry contracts against real filesystem faults. */
class MemoryStoreFailureContractTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedDeletionDoesNotAcknowledgeOrPublishAnUnsavedChange() {
        val file = File(folder.root, MemoryStore.FILE_NAME)
        val store = MemoryStore(file)
        val fact = store.add("Prefers casual Spanish", FactCategory.Preferences)!!
        val before = MemoryStore(file).load()

        // A directory at the temp-file path reliably rejects writeText, including as root.
        // A child makes it nonempty so neither a writer nor cleanup can mistake it for a file.
        val blocked = File(folder.root, MemoryStore.FILE_NAME + ".tmp")
        assertTrue(blocked.mkdir())
        File(blocked, "blocker").writeText("keep")
        val deletion = runCatching { store.delete(fact.id) }

        assertEquals("A failed delete must preserve the previously committed file", before, MemoryStore(file).load())
        assertEquals("Observers must not see a deletion that cannot survive restart", before, store.load())
        assertTrue("A failed delete must be reported as failure rather than Undo-able success",
            deletion.isFailure || deletion.getOrNull() == null)
    }

    @Test fun aFailedReadIsNotCachedAsAnEnabledEmptyInstall() {
        val file = File(folder.root, MemoryStore.FILE_NAME)
        assertTrue(file.mkdir())
        val store = MemoryStore(file)
        assertThrows(IOException::class.java) { store.load() }
        assertNull(store.state.value)

        assertTrue(file.delete())
        file.writeText("""{"enabled":false,"facts":[],"sources":{}}""")
        assertFalse(store.load().enabled)
        assertFalse(MemoryStore(file).load().enabled)
    }

    @Test fun failedOptOutPreservesTheAcknowledgedStateAndCanBeRetried() {
        val file = File(folder.root, MemoryStore.FILE_NAME)
        val store = MemoryStore(file)
        store.add("Prefers casual Spanish", FactCategory.Preferences)
        val before = store.load()
        val blocked = blockWrites(file)

        assertThrows(IOException::class.java) { store.setEnabled(false) }
        assertEquals(before, store.load())
        assertEquals(before, MemoryStore(file).load())

        assertTrue(blocked.deleteRecursively())
        store.setEnabled(false)
        assertFalse(store.load().enabled)
        assertEquals(store.load(), MemoryStore(file).load())
    }

    @Test fun corruptRecoveryIsDisabledAndPreservesEveryQuarantinedFile() {
        val file = File(folder.root, MemoryStore.FILE_NAME)
        val previous = File(folder.root, MemoryStore.FILE_NAME + ".unreadable")
        previous.writeText("older recoverable bytes")
        file.writeText("{new invalid bytes")

        val recovered = MemoryStore(file).load()

        assertFalse(recovered.enabled)
        assertTrue(recovered.facts.isEmpty())
        assertEquals(recovered, MemoryStore(file).load())
        assertEquals("older recoverable bytes", previous.readText())
        assertEquals("{new invalid bytes", File(folder.root, MemoryStore.FILE_NAME + ".unreadable.1").readText())
    }

    @Test fun failedRecoveryWriteCannotBecomeEnabledOnReopen() {
        val file = File(folder.root, MemoryStore.FILE_NAME).apply { writeText("{invalid") }
        val blocked = blockWrites(file)
        val store = MemoryStore(file)
        assertThrows(IOException::class.java) { store.load() }
        assertNull(store.state.value)
        assertEquals("{invalid", File(folder.root, MemoryStore.FILE_NAME + ".unreadable").readText())

        assertTrue(blocked.deleteRecursively())
        assertFalse(MemoryStore(file).load().enabled)
        assertFalse(store.load().enabled)
    }

    private fun blockWrites(file: File): File = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "blocker").writeText("keep")
    }
}
