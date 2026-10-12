package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HistoryListState(
    val rows: List<RecentRow> = emptyList(),
    val next: HistoryCursor? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/**
 * Loads older rows only on demand. Writes refresh the already loaded window, keeping
 * stable row IDs and enough rows for the list to preserve its scroll anchor.
 * All entry points run on the owning view model's main thread.
 */
internal class HistoryPager(
    private val scope: CoroutineScope,
    private val revisions: Flow<Long>,
    private val loadPage: suspend (HistoryCursor?, Int) -> HistoryPage?,
    private val row: (SessionSummary) -> RecentRow = { RecentRow(it, null) },
) {
    private val current = MutableStateFlow(HistoryListState())
    val state = current.asStateFlow()
    private var observing: Job? = null
    private var request: Job? = null
    private var generation = 0L
    private var restoreCount = PAGE_SIZE
    private var failedAppend = false

    /** A restored list can request enough rows to reach its saved scroll position. */
    fun start(minimumRows: Int = PAGE_SIZE) {
        if (observing != null) return
        restoreCount = minimumRows.coerceAtLeast(PAGE_SIZE)
        observing = scope.launch { revisions.collect { refresh() } }
    }

    fun loadMore() {
        val page = current.value
        if (page.loading || page.failed || page.next == null) return
        load(append = true)
    }

    fun retry() {
        if (!current.value.failed || current.value.loading) return
        load(append = failedAppend)
    }

    /** Drop an acknowledged deletion before the UI retires its temporary hidden ID. */
    fun remove(id: String) {
        current.value = current.value.copy(rows = current.value.rows.filterNot { it.summary.id == id })
        // A read started before deletion may still contain it. Invalidate that read too.
        if (observing != null) refresh()
    }

    private fun refresh() = load(append = false)

    private fun load(append: Boolean) {
        val before = current.value
        val epoch = ++generation
        request?.cancel()
        val cursor = if (append) before.next else null
        val limit = if (append) PAGE_SIZE else maxOf(PAGE_SIZE, restoreCount, before.rows.size)
        failedAppend = append
        current.value = before.copy(loading = true, failed = false)
        request = scope.launch {
            val page = loadPage(cursor, limit)
            if (page == null) {
                if (epoch == generation) current.value = before.copy(loading = false, failed = true)
                return@launch
            }
            val rows = withContext(Dispatchers.IO) { page.rows.map(row) }
            if (epoch != generation) return@launch
            restoreCount = PAGE_SIZE
            current.value = HistoryListState(
                rows = if (append) (before.rows + rows).distinctBy { it.summary.id } else rows,
                next = page.next,
                loading = false,
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 25
    }
}
