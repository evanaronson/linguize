package com.evanaronson.linguize.data.memory

import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.testing.KnownBonanzaDefect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.io.File

/** Future durability contract: the current store deliberately keeps failed writes in memory. */
@Category(KnownBonanzaDefect::class)
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
}
