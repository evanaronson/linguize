package com.evanaronson.linguize.ui.history

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import com.evanaronson.linguize.ui.settings.HistoryDataCard
import com.evanaronson.linguize.ui.settings.HistoryExportEffect
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.settings.keptHistoryTitle
import kotlinx.coroutines.flow.collect

/** Full history: stable, lazy rows with another bounded database page loaded near the end. */
@Composable
fun HistoryScreen(
    history: HistoryViewModel,
    settings: SettingsViewModel,
    historyOn: Boolean?,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val page by history.historyList.collectAsState()
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val time = rememberTimeFormat()
    HistoryDeletionFeedback(history, snackbar)
    HistoryExportEffect(settings)
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

    val title = keptHistoryTitle(settings)
    Box(Modifier.fillMaxSize()) {
        // Don't lay out an empty LazyColumn over a restored scroll position while loading.
        if (page.rows.isEmpty()) {
            ScreenColumn {
                title?.let { SectionHeader(it) }
                if (historyOn == false) SectionCard { HistoryOff(onOpenSettings) }
                when {
                    page.loading || (!page.failed && page.next != null) -> LoadingHistory()
                    page.failed -> HistoryRetry(history::retryHistory)
                    else -> Unit
                }
                HistoryDataCard(settings)
            }
        } else {
            val now = System.currentTimeMillis()
            val last = page.rows.lastIndex
            val hasFooter = page.loading || page.failed || page.next != null
            LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                item(key = "history-title", contentType = "title") { title?.let { SectionHeader(it) } }
                if (historyOn == false) item(key = "history-off", contentType = "notice") {
                    SectionCard(Modifier.padding(bottom = 12.dp)) { HistoryOff(onOpenSettings) }
                }
                itemsIndexed(page.rows, key = { _, row -> row.summary.id }, contentType = { _, _ -> "check" }) { index, row ->
                    Column(Modifier.cardSegment(first = index == 0, last = index == last && !hasFooter)) {
                        if (index > 0) RowDivider()
                        val delete = { history.delete(row.summary.id) }
                        SwipeToDelete(onDelete = delete) {
                            SessionRow(row, now, time, onClick = { onOpen(row.summary.id) }, onDelete = delete)
                        }
                    }
                }
                if (hasFooter) item(key = "history-footer", contentType = "footer") {
                    Box(Modifier.cardSegment(first = false, last = true), contentAlignment = Alignment.Center) {
                        when {
                            page.loading -> LoadingHistory()
                            page.failed -> HistoryRetry(history::retryHistory)
                            else -> LinguizeButton(stringResource(R.string.load_more_checks), history::loadMore, ButtonKind.Text,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                item(key = "history-data", contentType = "data") { HistoryDataCard(settings) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun LoadingHistory() {
    val label = stringResource(R.string.loading_history)
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label }, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HistoryRetry(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Quiet(stringResource(R.string.history_load_failed))
        LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Text)
    }
}
