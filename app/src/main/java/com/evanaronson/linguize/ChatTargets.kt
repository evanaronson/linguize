package com.evanaronson.linguize

import android.util.Log
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.PracticeTargetsBuilder
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lab Chat: the partner's practice targets block for a language, built off the main thread
 * from the corpus, the conjugation map and the vocabulary deck. Kept per language with the
 * corpus fingerprint it was built from; the corpus is read again at most every [freshMillis],
 * and a block that takes longer than [timeoutMillis] isn't waited for (the last one is used).
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
) {
    private data class Kept(val fingerprint: String, val block: String, val at: Long)

    private val kept = HashMap<String, Kept>()
    private val building = HashMap<String, Deferred<String>>()
    private val lock = Mutex()

    /** The block for [language]; "" when there is nothing to steer toward or it couldn't be built. */
    suspend fun block(language: Language): String {
        val code = language.code
        val last = lock.withLock { kept[code] }
        if (last != null && clock() - last.at < freshMillis) return last.block
        val job = lock.withLock {
            building[code]?.takeIf { it.isActive } ?: scope.async { build(code) }.also { building[code] = it }
        }
        return withTimeoutOrNull(timeoutMillis) { job.await() } ?: last?.block.orEmpty()
    }

    /** Starts building [language]'s block ahead of the first reply. */
    fun warm(language: Language) {
        scope.launch { runCatching { block(language) } }
    }

    private suspend fun build(code: String): String = withContext(Dispatchers.Default) {
        try {
            val corpus = utterances()
            val fingerprint = PracticeTargetsBuilder.fingerprint(corpus, code)
            lock.withLock { kept[code] }?.takeIf { it.fingerprint == fingerprint }?.let { same ->
                lock.withLock { kept[code] = same.copy(at = clock()) }
                return@withContext same.block
            }
            val lemmas = lemmatizer()
            val block = PracticeTargetsBuilder.build(corpus, code, morphology(), decks?.deck(code, lemmas), lemmas).render()
            lock.withLock { kept[code] = Kept(fingerprint, block, clock()) }
            block
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't build practice targets", e)
            ""
        }
    }

    companion object {
        private const val TAG = "LabChat"
        const val FRESH_MILLIS = 5 * 60 * 1000L
        const val TIMEOUT_MILLIS = 4_000L
    }
}
