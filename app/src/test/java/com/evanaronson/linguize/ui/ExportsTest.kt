package com.evanaronson.linguize.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** History exports don't outlive their use: replaced, cleared and pruned. */
class ExportsTest {
    @get:Rule
    val cache = TemporaryFolder()

    private var now = 10 * Exports.KEEP_MILLIS
    private val exports by lazy { Exports(cache.root) { now } }
    private val folder get() = File(cache.root, Exports.FOLDER)

    private fun write(date: LocalDate): File = exports.create(date).apply { writeText("{}") }

    @Test
    fun aNewExportReplacesEarlierOnes() {
        val first = write(LocalDate.of(2026, 10, 8))
        val second = write(LocalDate.of(2026, 10, 9))
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(File(folder, "linguize-history-2026-10-09.jsonl"), second)
    }

    @Test
    fun clearRemovesEveryExport() {
        write(LocalDate.of(2026, 10, 9))
        File(folder, "partial.jsonl").writeText("{")
        exports.clear()
        assertEquals(0, folder.listFiles()!!.size)
    }

    @Test
    fun clearWithNoFolderDoesNothing() {
        exports.clear()
        exports.prune()
        assertFalse(folder.exists())
    }

    @Test
    fun pruneKeepsAnExportForAFewMinutesOnly() {
        val file = write(LocalDate.of(2026, 10, 9))
        file.setLastModified(now - Exports.KEEP_MILLIS + 1_000)
        exports.prune()
        assertTrue("still being shared", file.exists())

        now += 2_000
        exports.prune()
        assertFalse(file.exists())
    }
}
