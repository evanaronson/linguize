package com.evanaronson.linguize.data.vocabgaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The store owns write ordering; neither caller has a ViewModel mutex. */
class VocabSelectionOrderingTest {
    @get:Rule val folder = TemporaryFolder()
    private val olderChoice = VocabSelection("es", "bundled-es")
    private val newerChoice = VocabSelection("ca", "bundled-ca")

    @Test fun anAuthorizedOlderWriteFinishesBeforeTheNewerChoiceAcrossStoreInstances() {
        val olderStore = VocabGapsStore(folder.root)
        val newerStore = VocabGapsStore(folder.root)
        val query = AtomicLong(1)
        val olderAuthorized = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val newerStarted = CountDownLatch(1)
        val newerThread = AtomicReference<Thread>()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val older = workers.submit<Boolean> {
                var checks = 0
                olderStore.saveSelection(olderChoice) {
                    val current = query.get() == 1L
                    if (++checks == 2) {
                        // Hold the last predicate under the store lock, before the synchronous
                        // write. This models a write already authorized when the query changes.
                        olderAuthorized.countDown()
                        check(releaseOlder.await(10, SECONDS))
                    }
                    current
                }
            }
            assertTrue(olderAuthorized.await(10, SECONDS))
            query.set(2)
            val newer = workers.submit<Boolean> {
                newerThread.set(Thread.currentThread())
                newerStarted.countDown()
                newerStore.saveSelection(newerChoice) {
                    // Reentrant read: the older commit must precede this write's admission.
                    assertEquals(olderChoice, newerStore.selection())
                    query.get() == 2L
                }
            }
            assertTrue(newerStarted.await(10, SECONDS))
            val thread = newerThread.get()
            val deadline = System.nanoTime() + SECONDS.toNanos(10)
            while (thread.state != Thread.State.BLOCKED && !newer.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("The other store must wait for the shared lock", Thread.State.BLOCKED, thread.state)
            releaseOlder.countDown()

            assertTrue(older.get(10, SECONDS))
            assertTrue(newer.get(10, SECONDS))
            assertEquals(newerChoice, VocabGapsStore(folder.root).selection())
        } finally {
            releaseOlder.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, SECONDS))
        }
    }

    @Test fun anOlderCallerArrivingAfterTheNewerCommitCannotOverwriteIt() {
        val olderStore = VocabGapsStore(folder.root)
        val newerStore = VocabGapsStore(folder.root)
        val query = AtomicLong(2)

        assertTrue(newerStore.saveSelection(newerChoice) { query.get() == 2L })
        assertFalse(olderStore.saveSelection(olderChoice) { query.get() == 1L })
        assertEquals(newerChoice, VocabGapsStore(folder.root).selection())
    }
}
