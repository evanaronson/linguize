package com.evanaronson.linguize.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Shared ownership and retirement for captured Words evidence and live History writers. */
class WordsExportsTest {
    @get:Rule val cache = TemporaryFolder()
    private val exports by lazy { Exports(cache.root) }
    private val folder get() = File(cache.root, Exports.FOLDER)
    private val date = LocalDate.of(2026, 11, 10)

    private suspend fun history() = exports.write(date) { it.write("history".toByteArray()); true }!!
    private suspend fun words(request: Exports.WordsRequest = exports.requestWords(exports.generation())) =
        exports.writeWords(date, "ca", request) { it.write("words".toByteArray()); true }

    @Test fun wordsReplacesWordsOnlyAndHistoryStillReplacesEveryOlderExport() = runBlocking {
        val generation = exports.generation()
        val historyFile = history()
        val first = words()!!
        val second = words()!!
        assertTrue(second.name.startsWith("linguize-words-ca-2026-11-10-"))
        assertTrue(second.name.endsWith(".csv"))
        assertNotEquals(first, second)
        assertFalse(first.exists())
        assertFalse(exports.isCurrent(first))
        assertTrue(historyFile.exists())
        assertTrue(exports.isCurrent(historyFile))
        assertTrue(exports.isCurrent(second))

        exports.discard(first) // An older host cannot retire either newer result.
        assertTrue(second.exists())
        assertTrue(historyFile.exists())
        val latestHistory = history()
        assertFalse(historyFile.exists())
        assertFalse(second.exists())
        assertTrue(exports.isCurrent(latestHistory))
        assertEquals("Export replacement does not invalidate coherent evidence", generation, exports.generation())
        assertNotNull(words(exports.requestWords(generation)))
    }

    @Test fun aSnapshotCapturedBeforeClearCannotBeBlessedByALaterExportClick() = runBlocking {
        val captured = exports.generation()
        exports.clear()
        val latestHistory = history()
        val latestWords = words()!!
        var invoked = false

        val stale = exports.writeWords(date, "ca", exports.requestWords(captured)) {
            invoked = true
            true
        }

        assertNull(stale)
        assertFalse(invoked)
        assertTrue(latestHistory.exists())
        assertTrue(latestWords.exists())
        assertTrue(exports.isCurrent(latestWords))
    }

    @Test fun anOlderHostCannotReplaceANewerWordsExportEvenWhenItsIoStartsLast() = runBlocking {
        val earlier = exports.requestWords(exports.generation())
        val newer = exports.requestWords(exports.generation())
        val current = words(newer)!!

        assertNull(words(earlier))
        assertTrue(current.exists())
        assertTrue(exports.isCurrent(current))
        assertEquals("words", current.readText())
    }

    @Test fun acknowledgedClearInvalidatesBothHostsBeforeWaitingForTheActiveWriter() = runBlocking {
        withTimeout(5_000) {
            val historyFile = history()
            val generation = exports.generation()
            val request = exports.requestWords(generation)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val writing = async {
                exports.writeWords(date, "ca", request) {
                    it.write("private words".toByteArray())
                    entered.complete(Unit)
                    finish.await()
                    true
                }
            }
            entered.await()
            val clearing = async(start = CoroutineStart.UNDISPATCHED) { exports.clearAcknowledged() }
            assertFalse(clearing.isCompleted)
            assertNotEquals(generation, exports.generation())
            assertFalse("Share must stop before filesystem cleanup can acquire the mutex", exports.isCurrent(historyFile))

            finish.complete(Unit)
            assertNull(writing.await())
            assertTrue(clearing.await())
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun clearRejectsACapturedWordsWriterAlreadyWaitingOnTheSharedMutex() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val history = async {
                exports.write(date) {
                    entered.complete(Unit)
                    finish.await()
                    true
                }
            }
            entered.await()
            val request = exports.requestWords(exports.generation())
            var wroteWords = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                exports.writeWords(date, "ca", request) { wroteWords = true; true }
            }
            val clearing = async(start = CoroutineStart.UNDISPATCHED) { exports.clear() }
            assertFalse(queued.isCompleted)
            finish.complete(Unit)

            assertNull(history.await())
            assertNull(queued.await())
            clearing.await()
            assertFalse(wroteWords)
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun aStaleSnapshotRequestDoesNotCancelAnotherHostsValidPendingWriter() = runBlocking {
        withTimeout(5_000) {
            val oldGeneration = exports.generation()
            exports.clear()
            val request = exports.requestWords(exports.generation())
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val writing = async {
                exports.writeWords(date, "ca", request) {
                    entered.complete(Unit)
                    finish.await()
                    true
                }
            }
            entered.await()
            val stale = exports.requestWords(oldGeneration)
            finish.complete(Unit)
            val current = writing.await()!!

            assertNull(words(stale))
            assertTrue(current.exists())
            assertTrue(exports.isCurrent(current))
        }
    }

    @Test fun cancellingWordsRemovesItsPartialFileAndLeavesHistoryAvailable() = runBlocking {
        withTimeout(5_000) {
            val historyFile = history()
            val request = exports.requestWords(exports.generation())
            val entered = CompletableDeferred<Unit>()
            val writing = launch {
                exports.writeWords(date, "ca", request) {
                    it.write("partial words".toByteArray())
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            writing.cancelAndJoin()

            assertEquals(listOf(historyFile), folder.listFiles()!!.toList())
            assertTrue(exports.isCurrent(historyFile))
        }
    }
}
