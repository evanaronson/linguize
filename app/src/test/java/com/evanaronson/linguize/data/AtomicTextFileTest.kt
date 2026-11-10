package com.evanaronson.linguize.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicTextFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun replacementPublishesTheCompleteUtf8File() {
        val file = File(folder.root, "nested/state.json")
        writeAtomically(file, "old")
        writeAtomically(file, "Bon dia, veí 👋")

        assertEquals("Bon dia, veí 👋", file.readText())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun failedTemporaryWritePreservesTheDestinationAndAllowsRetry() {
        val file = File(folder.root, "state.json")
        file.writeText("saved")
        val blocked = File(folder.root, "state.json.tmp")
        assertTrue(blocked.mkdir())
        File(blocked, "keep").writeText("obstruction")

        assertThrows(IOException::class.java) { writeAtomically(file, "new") }
        assertEquals("saved", file.readText())
        assertEquals("obstruction", File(blocked, "keep").readText())

        assertTrue(blocked.deleteRecursively())
        writeAtomically(file, "new")
        assertEquals("new", file.readText())
    }

    @Test fun failedReplacementPreservesTheDestinationAndRemovesOnlyTheTemporaryFile() {
        val destination = folder.newFolder("state.json")
        File(destination, "keep").writeText("saved")

        assertThrows(IOException::class.java) { writeAtomically(destination, "new") }
        assertEquals("saved", File(destination, "keep").readText())
        assertFalse(File(folder.root, "state.json.tmp").exists())
    }

    @Test fun failedParentCreationDoesNotReplaceAnExistingFile() {
        val parent = File(folder.root, "parent")
        parent.writeText("saved")

        assertThrows(IOException::class.java) { writeAtomically(File(parent, "state.json"), "new") }
        assertEquals("saved", parent.readText())
    }
}
