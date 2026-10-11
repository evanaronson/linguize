package com.evanaronson.linguize.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private suspend fun write(date: LocalDate): File = exports.write(date) { it.write("{}".toByteArray()); true }!!

    @Test
    fun aNewExportReplacesEarlierOnes() = runBlocking {
        val first = write(LocalDate.of(2026, 10, 8))
        val second = write(LocalDate.of(2026, 10, 9))
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(folder, second.parentFile)
        assertTrue(second.name.startsWith("linguize-history-2026-10-09-"))
        assertTrue(second.name.endsWith(".jsonl"))
    }

    @Test
    fun clearRemovesEveryExport() = runBlocking {
        write(LocalDate.of(2026, 10, 9))
        File(folder, "partial.jsonl").writeText("{")
        exports.clear()
        assertEquals(0, folder.listFiles()!!.size)
    }

    @Test
    fun clearWithNoFolderDoesNothing() = runBlocking {
        exports.clear()
        exports.prune()
        assertFalse(folder.exists())
    }

    @Test
    fun pruneKeepsAnExportForAFewMinutesOnly() = runBlocking {
        val file = write(LocalDate.of(2026, 10, 9))
        file.setLastModified(now - Exports.KEEP_MILLIS + 1_000)
        exports.prune()
        assertTrue("still being shared", file.exists())

        now += 2_000
        exports.prune()
        assertFalse(file.exists())
    }

    @Test
    fun olderUriNeverNamesANewerExportAndStaleCleanupCannotRemoveIt() = runBlocking {
        val date = LocalDate.of(2026, 10, 9)
        val first = write(date)
        val second = write(date)
        assertNotEquals(first, second)
        assertFalse(first.exists())
        assertFalse(exports.isCurrent(first))
        exports.discard(first)
        assertTrue(second.exists())
        assertTrue(exports.isCurrent(second))
    }

    @Test
    fun unsuccessfulWriteLeavesNoPartialFile() = runBlocking {
        val file = exports.write(LocalDate.of(2026, 10, 9)) {
            it.write("partial history".toByteArray())
            false
        }
        assertNull(file)
        assertEquals(0, folder.listFiles()!!.size)
    }

    @Test
    fun cancellingAWriterRemovesItsPartialFile() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val writing = launch {
                exports.write(LocalDate.of(2026, 10, 9)) {
                    it.write("partial history".toByteArray())
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            writing.cancelAndJoin()
            assertEquals(0, folder.listFiles()!!.size)
        }
    }

    @Test
    fun clearWaitsForAnActiveWriterAndRemovesItsResult() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val writing = async {
                exports.write(LocalDate.of(2026, 10, 9)) {
                    started.complete(Unit)
                    finish.await()
                    it.write("private history".toByteArray())
                    true
                }!!
            }
            started.await()
            val clearing = async { exports.clear() }
            yield()
            assertFalse("clear cannot finish before the writer", clearing.isCompleted)
            finish.complete(Unit)
            val file = writing.await()
            clearing.await()
            assertFalse(file.exists())
            assertFalse(exports.isCurrent(file))
        }
    }

    @Test
    fun movingTheClockBackDoesNotKeepExportsIndefinitely() = runBlocking {
        val file = write(LocalDate.of(2026, 10, 9))
        file.setLastModified(now)
        now -= 60_000
        exports.prune()
        assertFalse(file.exists())
    }
}
