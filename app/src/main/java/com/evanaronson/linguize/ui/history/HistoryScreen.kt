package com.evanaronson.linguize.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import kotlinx.coroutines.flow.collect

/** Full history: stable, lazy rows with another bounded database page loaded near the end. */
@Composable
fun HistoryScreen(
    history: HistoryViewModel,
    historyOn: Boolean?,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val page by history.historyList.collectAsState()
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val time = rememberTimeFormat()
    HistoryUndo(history, snackbar)
    LaunchedEffect(history) {
        history.showHistory(list.firstVisibleItemIndex + HistoryPager.PAGE_SIZE)
    }
    LaunchedEffect(history, list) {
        snapshotFlow {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            page.next.takeIf {
                (page.rows.isEmpty() || last >= page.rows.size - 5) && !page.loading && !page.failed
            }
        }.collect { cursor -> if (cursor != null) history.loadMore() }
    }

    Box(Modifier.fillMaxSize()) {
        // Don't lay out an empty LazyColumn over a restored scroll position while loading.
        if (page.rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (historyOn == false) HistoryOff(onOpenSettings)
                when {
                    page.loading || (!page.failed && page.next != null) -> LoadingHistory()
                    page.failed -> HistoryRetry(history::retryHistory)
                    else -> Quiet(stringResource(R.string.no_checks_saved))
                }
            }
        } else {
            val now = System.currentTimeMillis()
            LazyColumn(state = list, contentPadding = PaddingValues(20.dp)) {
                if (historyOn == false) item(key = "history-off", contentType = "notice") { HistoryOff(onOpenSettings) }
                itemsIndexed(page.rows, key = { _, row -> row.summary.id }, contentType = { _, _ -> "check" }) { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val delete = { history.delete(row.summary.id) }
                    SwipeToDelete(onDelete = delete) {
                        SessionRow(row, now, time, onClick = { onOpen(row.summary.id) }, onDelete = delete)
                    }
                }
                item(key = "history-footer", contentType = "footer") {
                    when {
                        page.loading -> LoadingHistory()
                        page.failed -> HistoryRetry(history::retryHistory)
                        page.next != null -> TextButton(onClick = history::loadMore) { Text(stringResource(R.string.load_more_checks)) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LoadingHistory() {
    val label = stringResource(R.string.loading_history)
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun HistoryRetry(onRetry: () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Quiet(stringResource(R.string.history_load_failed))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
