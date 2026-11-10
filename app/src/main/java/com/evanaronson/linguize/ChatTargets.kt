package com.evanaronson.linguize

import android.util.Log
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.PracticeTargetsBuilder
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Lab Chat: the partner's practice targets block for a language, built off the main thread
 * from the corpus, the conjugation map and the vocabulary deck. Kept per language with the
 * corpus fingerprint and History revision it was built from. A known unchanged revision is
 * read again at most every [freshMillis]. A slow or failed refresh may use the last block only
 * while that revision still matches; deleted evidence never qualifies for the fallback.
 */
class ChatTargets(
    private val utterances: suspend () -> List<Utterance>,
    private val morphology: () -> Morphology?,
    private val lemmatizer: () -> Lemmatizer,
    private val decks: BundledDecks?,
    /** Where a build runs, so one the partner stopped waiting for still finishes and is kept. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshMillis: Long = FRESH_MILLIS,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    /** Synchronous commit revision; null means freshness/deletion safety cannot be inferred. */
    private val evidenceRevision: () -> Long? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Kept(val revision: Long?, val fingerprint: String, val block: String, val at: Long)
    private data class Building(val revision: Long?, val job: Deferred<Kept?>)

    private val kept = HashMap<String, Kept>()
    private val building = HashMap<String, Building>()
    private val lock = Mutex()

    /** The block for [language]; "" when there is nothing to steer toward or it couldn't be built. */
    suspend fun block(language: Language): String {
        val code = language.code
        val last = lock.withLock { kept[code] }
        if (last?.revision != null && current(last.revision) && clock() - last.at < freshMillis) return last.block
        val job = lock.withLock {
            val revision = evidenceRevision()
            if (kept[code]?.revision != revision) kept.remove(code)
            building[code]?.takeIf { it.revision == revision && it.job.isActive }?.job
                ?: scope.async { build(code, revision) }.also { building[code] = Building(revision, it) }
        }
        val built = withTimeoutOrNull(timeoutMillis) { job.await() }
        if (built != null && current(built.revision)) return built.block
        // Recheck at return, not against the revision captured before waiting. Another write
        // can revoke the previous block while this caller waits for a build or its timeout.
        return lock.withLock { kept[code]?.takeIf { it.revision != null && current(it.revision) }?.block.orEmpty() }
    }

    /** Starts building [language]'s block ahead of the first reply. */
    fun warm(language: Language): Job = scope.launch { block(language) }

    private fun current(revision: Long?) = evidenceRevision() == revision

    private suspend fun build(code: String, revision: Long?): Kept? = withContext(dispatcher) {
        try {
            if (!current(revision)) return@withContext null
            // The corpus callback is strict: partial or failed evidence throws, never an empty list.
            val corpus = utterances()
            if (!current(revision)) return@withContext null
            val fingerprint = PracticeTargetsBuilder.fingerprint(corpus, code)
            val same = lock.withLock { kept[code] }?.takeIf { it.revision == revision && it.fingerprint == fingerprint }
            val block = if (same != null) same.block else {
                val lemmas = lemmatizer()
                val deck = decks?.deck(code, lemmas)
                if (decks != null && code in BundledDecks.languages && deck == null) {
                    throw IOException("The bundled vocabulary deck couldn't be read")
                }
                PracticeTargetsBuilder.build(corpus, code, morphology(), deck, lemmas).render()
            }
            if (!current(revision)) return@withContext null
            val result = Kept(revision, fingerprint, block, clock())
            lock.withLock {
                if (!current(revision)) return@withContext null
                kept[code] = result
            }
            result.takeIf { current(revision) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't build practice targets", e)
            null
        }
    }

    companion object {
        private const val TAG = "LabChat"
        const val FRESH_MILLIS = 5 * 60 * 1000L
        const val TIMEOUT_MILLIS = 4_000L
    }
}
