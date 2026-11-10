package com.evanaronson.linguize

import android.app.Application
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.PracticeTargetsBuilder
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Real target analysis with scheduled shared builds, transient failures and commit revisions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatTargetsTest {
    @get:Rule val main = MainDispatcherRule()
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private var revision: Long? = 0L
    private val neutral = (1..8).map { index ->
        Utterance("check:$index", "check", "es", "Hoy trabajo en casa.", at = index.toLong(), origin = "menu")
    }
    private val formal = neutral.map { it.copy(text = "Estimado señor, le escribo atentamente.") }

    @After fun close() { scope.cancel() }

    private fun targets(
        read: suspend () -> List<Utterance>,
        decks: BundledDecks? = null,
        morphology: () -> Morphology? = { null },
    ) = ChatTargets(
        read, morphology, { Lemmatizer.Identity }, decks, scope,
        clock = { main.dispatcher.scheduler.currentTime }, freshMillis = FRESH, timeoutMillis = WAIT,
        evidenceRevision = { revision }, dispatcher = main.dispatcher,
    )

    private fun expected(input: List<Utterance>, language: String = "es") =
        PracticeTargetsBuilder.build(input, language, null, null, Lemmatizer.Identity).render()

    @Test fun unchangedKnownRevisionKeepsSuccessfulTargetsForTheExistingFreshnessWindow() = runTest(main.dispatcher) {
        var reads = 0
        val targets = targets({ reads++; neutral })
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(1, reads)
        advanceTimeBy(FRESH - 1)
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(1, reads)
        advanceTimeBy(1)
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(2, reads)
        assertEquals("", targets.block(Language.Catalan))
        assertEquals(3, reads)
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(3, reads)
    }

    @Test fun aFailedFirstReadDoesNotBecomeAFreshEmptyResult() = runTest(main.dispatcher) {
        var reads = 0
        val targets = targets({ if (++reads == 1) throw IOException("History unavailable") else neutral })
        assertEquals("", targets.block(Language.Spanish))
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(2, reads)
    }

    @Test fun partialCorpusEvidenceIsNotAnalysedOrCachedAsComplete() = runTest(main.dispatcher) {
        var unavailable = true
        var failedSourceReads = 0
        val corpus = Corpus(listOf(
            object : CorpusSource {
                override val name = "available"
                override suspend fun utterances() = neutral
            },
            object : CorpusSource {
                override val name = "unavailable"
                override suspend fun utterances(): List<Utterance> {
                    failedSourceReads++
                    if (unavailable) throw IOException("One source failed")
                    return emptyList()
                }
            },
        ))
        val targets = targets({ corpus.utterances() })
        assertEquals("", targets.block(Language.Spanish))
        unavailable = false
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(2, failedSourceReads)
    }

    @Test fun aFailedRefreshCanUseOnlyTheSameRevisionsLastGoodBlockAndRetries() = runTest(main.dispatcher) {
        var reads = 0
        var unavailable = false
        val targets = targets({ reads++; if (unavailable) throw IOException("Read failed") else neutral })
        val good = targets.block(Language.Spanish)
        advanceTimeBy(FRESH)
        unavailable = true
        assertEquals(good, targets.block(Language.Spanish))
        assertEquals(good, targets.block(Language.Spanish))
        assertEquals(3, reads)
        unavailable = false
        assertEquals(good, targets.block(Language.Spanish))
        assertEquals(4, reads)
        assertEquals(good, targets.block(Language.Spanish))
        assertEquals(4, reads)
    }

    @Test fun aRevisionChangeRevokesEvenFreshTargetsAndFailedReadFallback() = runTest(main.dispatcher) {
        var reads = 0
        var unavailable = false
        var input = neutral
        val targets = targets({ reads++; if (unavailable) throw IOException("Deleted History unavailable") else input })
        val old = targets.block(Language.Spanish)
        assertTrue(old.isNotEmpty())
        revision = 1L
        unavailable = true
        assertEquals("", targets.block(Language.Spanish))
        unavailable = false
        input = formal
        val current = targets.block(Language.Spanish)
        assertEquals(expected(formal), current)
        assertNotEquals(old, current)
        assertEquals(3, reads)
    }

    @Test fun aTimeoutCannotReturnTheBlockCapturedBeforeDeletion() = runTest(main.dispatcher) {
        var reads = 0
        val gate = CompletableDeferred<List<Utterance>>()
        val targets = targets({ if (++reads == 1) neutral else gate.await() })
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        advanceTimeBy(FRESH)
        val waiting = async { targets.block(Language.Spanish) }
        runCurrent()
        revision = 1L
        advanceTimeBy(WAIT)
        runCurrent()
        assertEquals("", waiting.await())
        // Its same-fingerprint old read finishes after deletion and cannot restore that cache.
        gate.complete(neutral)
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun aLateOldBuildCannotOverwriteTheNewRevisionsTargets() = runTest(main.dispatcher) {
        var reads = 0
        val gate = CompletableDeferred<List<Utterance>>()
        val targets = targets({
            when (++reads) {
                1 -> neutral
                2 -> gate.await()
                else -> formal
            }
        })
        targets.block(Language.Spanish)
        advanceTimeBy(FRESH)
        val oldRequest = async { targets.block(Language.Spanish) }
        runCurrent()
        revision = 1L
        val current = targets.block(Language.Spanish)
        assertEquals(expected(formal), current)
        gate.complete(neutral)
        runCurrent()
        assertEquals(current, oldRequest.await())
        assertEquals(current, targets.block(Language.Spanish))
        assertEquals(3, reads)
    }

    @Test fun aTimeoutWithUnchangedEvidenceKeepsTheSharedBuildRunning() = runTest(main.dispatcher) {
        var reads = 0
        val gate = CompletableDeferred<List<Utterance>>()
        val targets = targets({ if (++reads == 1) neutral else gate.await() })
        val good = targets.block(Language.Spanish)
        advanceTimeBy(FRESH)
        val waiting = async { targets.block(Language.Spanish) }
        runCurrent()
        advanceTimeBy(WAIT)
        runCurrent()
        assertEquals(good, waiting.await())
        assertFalse(gate.isCancelled)
        gate.complete(neutral)
        runCurrent()
        assertEquals(good, targets.block(Language.Spanish))
        assertEquals(2, reads)
    }

    @Test fun unknownRevisionRequiresAReadAndCannotReuseFailureFallback() = runTest(main.dispatcher) {
        revision = null
        var reads = 0
        val targets = targets({ if (++reads == 2) throw IOException("Deletion safety unknown") else neutral })
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals("", targets.block(Language.Spanish))
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(3, reads)
    }

    @Test fun aSuccessfulEmptyReadIsCacheableButAFailedReadIsRetried() = runTest(main.dispatcher) {
        var reads = 0
        val targets = targets({ if (++reads > 1) throw IOException("Unavailable") else emptyList() })
        assertEquals("", targets.block(Language.Spanish))
        assertEquals("", targets.block(Language.Spanish))
        assertEquals(1, reads)
        advanceTimeBy(FRESH)
        assertEquals("", targets.block(Language.Spanish))
        assertEquals("", targets.block(Language.Spanish))
        assertEquals(3, reads)
    }

    @Test fun sourceCancellationPropagatesAndTheNextRequestCanRetry() = runTest(main.dispatcher) {
        var reads = 0
        val targets = targets({ if (++reads == 1) throw CancellationException("Cancelled read") else neutral })
        try {
            targets.block(Language.Spanish)
            fail("Source cancellation must propagate")
        } catch (_: CancellationException) {
            // Cancellation is distinct from an unavailable optional target block.
        }
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(2, reads)
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheSharedBuildOrTheOtherWaiter() = runTest(main.dispatcher) {
        var reads = 0
        val gate = CompletableDeferred<List<Utterance>>()
        val targets = targets({ reads++; gate.await() })
        val first = async { targets.block(Language.Spanish) }
        val second = async { targets.block(Language.Spanish) }
        runCurrent()
        assertEquals(1, reads)
        first.cancelAndJoin()
        assertFalse(gate.isCancelled)
        gate.complete(neutral)
        runCurrent()
        assertEquals(expected(neutral), second.await())
        assertEquals(1, reads)
    }

    @Test fun warmingDoesNotSwallowSourceCancellation() = runTest(main.dispatcher) {
        val targets = targets({ throw CancellationException("Warm read cancelled") })
        val warming = targets.warm(Language.Spanish)
        runCurrent()
        assertTrue(warming.isCancelled)
    }

    @Test fun anAnalysisFailureDoesNotCacheAPartialOrEmptyBlock() = runTest(main.dispatcher) {
        var unavailable = true
        var reads = 0
        val targets = targets({ reads++; neutral }, morphology = {
            if (unavailable) throw IOException("Morphology unavailable")
            null
        })
        assertEquals("", targets.block(Language.Spanish))
        unavailable = false
        assertEquals(expected(neutral), targets.block(Language.Spanish))
        assertEquals(2, reads)
    }

    @Test fun aConfiguredBundledDeckFailureRetriesInsteadOfCachingRegisterOnlyTargets() = runTest(main.dispatcher) {
        var deckReads = 0
        val decks = BundledDecks {
            if (++deckReads == 1) throw IOException("Asset unavailable")
            "lemma,gloss\ncasa,house\nplaya,beach"
        }
        val targets = targets({ neutral }, decks = decks)
        assertEquals("", targets.block(Language.Spanish))
        val recovered = targets.block(Language.Spanish)
        assertTrue(recovered.contains("playa (beach)"))
        assertTrue(recovered.contains("formal writing"))
        assertEquals(2, deckReads)
    }

    @Test fun bundledDeckCancellationPropagatesThroughTargets() = runTest(main.dispatcher) {
        val targets = targets({ neutral }, decks = BundledDecks { throw CancellationException("Asset cancelled") })
        try {
            targets.block(Language.Spanish)
            fail("Deck cancellation must propagate")
        } catch (_: CancellationException) {
            // Shared deck loading must not collapse cancellation into a missing optional deck.
        }
    }

    @Test fun unsupportedBundledLanguagesRemainValidWithoutAnAssetRead() = runTest(main.dispatcher) {
        var reads = 0
        val targets = targets({ reads++; neutral }, decks = BundledDecks { error("No English deck should be requested") })
        assertEquals("", targets.block(Language("en", "English")))
        assertEquals("", targets.block(Language("en", "English")))
        assertEquals(1, reads)
    }

    private companion object {
        const val FRESH = 100L
        const val WAIT = 20L
    }
}
