package com.evanaronson.linguize.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shares one store's newest successful window across evidence consumers. Smaller requests
 * use the selected row ranks, including missing slots, so their historical limits stay fixed.
 * Only stores that bind a snapshot to a synchronous revision can participate in caching.
 */
class HistoryEvidenceReader(private val history: HistoryStore) {
    private val mutex = Mutex()
    private var cached: HistoryEvidenceSnapshot? = null

    fun revision(): Long? = history.evidenceRevision()

    /** Releases obsolete source text even when no consumer asks for another read. */
    suspend fun watch() {
        history.revisions().collect {
            mutex.withLock { discardObsolete() }
        }
    }

    suspend fun read(limit: Int): HistoryEvidenceResult = mutex.withLock {
        val size = limit.coerceIn(1, Int.MAX_VALUE - 1)
        repeat(2) {
            currentCoroutineContext().ensureActive()
            discardObsolete()
            cached?.let { snapshot ->
                if (snapshot.window.limit >= size || !snapshot.window.hasMore) {
                    val result = snapshot.prefix(size)
                    currentCoroutineContext().ensureActive()
                    if (snapshot.revision == revision()) return@withLock HistoryEvidenceResult.Ready(result)
                    cached = null
                }
            }

            val before = revision()
            val result = try {
                history.evidence(size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                HistoryEvidenceResult.Failed
            }
            currentCoroutineContext().ensureActive()
            if (result is HistoryEvidenceResult.Failed) {
                discardObsolete()
                return@withLock result
            }
            val snapshot = (result as HistoryEvidenceResult.Ready).snapshot
            val after = revision()
            if (before != after || (snapshot.revision != null && snapshot.revision != after)) {
                cached = null
                // A committed write overtook the read. Retry once, without publishing its old text.
                return@repeat
            }
            if (after != null && snapshot.revision == after) {
                cached = snapshot.copy(details = snapshot.details.toList(), selectedIds = snapshot.selectedIds.toList())
            }
            val window = snapshot.prefix(size)
            currentCoroutineContext().ensureActive()
            if (after != revision()) {
                cached = null
                return@repeat
            }
            return@withLock HistoryEvidenceResult.Ready(window)
        }
        HistoryEvidenceResult.Failed
    }

    private fun discardObsolete() {
        val current = revision()
        if (current == null || cached?.revision != current) cached = null
    }

    private fun HistoryEvidenceSnapshot.prefix(limit: Int): HistoryEvidenceSnapshot {
        val selected = selectedIds.take(limit)
        val ids = selected.toHashSet()
        return copy(
            window = HistoryEvidenceWindow(limit, window.hasMore || selectedIds.size > limit),
            details = details.filter { it.session.id in ids },
            selectedIds = selected,
        )
    }
}
