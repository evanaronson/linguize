package com.evanaronson.linguize.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A successfully read newest-first window. [hasMore] means older visible checks exist. */
data class HistoryEvidenceWindow(val limit: Int, val hasMore: Boolean)

/**
 * Evidence from one read. SQLite binds [revision] to the rows in its transaction; adapters
 * without that guarantee return null and must not be cached as a known revision.
 * [selectedIds] retains the ranked slots even if a paging adapter finds a row removed before
 * its detail read. A smaller window must slice these slots, not pull in extra found details.
 */
data class HistoryEvidenceSnapshot(
    val revision: Long?,
    val window: HistoryEvidenceWindow,
    val details: List<SessionDetail>,
    val selectedIds: List<String> = details.map { it.session.id },
)

/** A successful empty window is Ready with no rows; an unreadable window is never empty evidence. */
sealed interface HistoryEvidenceResult {
    data class Ready(val snapshot: HistoryEvidenceSnapshot) : HistoryEvidenceResult
    data object Failed : HistoryEvidenceResult
}

/** Compatibility for stores with only page/detail APIs; SQLite overrides this with a transaction. */
internal suspend fun HistoryStore.readEvidencePages(limit: Int): HistoryEvidenceResult {
    return try {
        val size = limit.coerceIn(1, Int.MAX_VALUE - 1)
        val selected = mutableListOf<String>()
        var cursor: HistoryCursor? = null
        var hasMore: Boolean
        do {
            currentCoroutineContext().ensureActive()
            val remaining = size - selected.size
            val page = page(cursor, minOf(remaining, EVIDENCE_PAGE_SIZE)) ?: return HistoryEvidenceResult.Failed
            selected += page.rows.take(remaining).map { it.id }
            hasMore = page.next != null || page.rows.size > remaining
            if (page.rows.isEmpty() && page.next != null) return HistoryEvidenceResult.Failed
            cursor = page.next
        } while (cursor != null && selected.size < size)

        val details = buildList {
            for (id in selected.distinct()) {
                currentCoroutineContext().ensureActive()
                when (val result = detail(id)) {
                    is SessionDetailResult.Found -> add(result.detail)
                    SessionDetailResult.Missing -> Unit
                    SessionDetailResult.Failed -> return HistoryEvidenceResult.Failed
                }
            }
        }
        currentCoroutineContext().ensureActive()
        HistoryEvidenceResult.Ready(HistoryEvidenceSnapshot(null, HistoryEvidenceWindow(size, hasMore), details, selected.toList()))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        HistoryEvidenceResult.Failed
    }
}

private const val EVIDENCE_PAGE_SIZE = 100
