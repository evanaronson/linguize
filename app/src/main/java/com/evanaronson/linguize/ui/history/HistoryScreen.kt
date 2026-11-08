package com.evanaronson.linguize.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
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

/**
 * Full history: one list of messages, one row each whatever app (or the Lab Chat) they were
 * written in, under day headers. Every row opens its saved check. An optional source filter
 * sits on top. Rows are lazy, with another bounded database page loaded near the end.
 */
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
    val resources = LocalResources.current
    var filterToken by rememberSaveable { mutableStateOf(HistoryFilter.All.token) }
    val filter = HistoryFilter.read(filterToken)
    val choices = remember(page.rows, filter) { filterChoices(page.rows, filter) }
    val items = remember(page.rows, filter) { historyItems(page.rows.filter(filter::keeps)) }
    val shownCount by rememberUpdatedState(items.size)
    HistoryDeletionFeedback(history, snackbar)
    HistoryExportEffect(settings)
    LaunchedEffect(history) {
        history.showHistory(list.firstVisibleItemIndex + HistoryPager.PAGE_SIZE)
    }
    LaunchedEffect(history, list) {
        snapshotFlow {
            // Indices count headers too; a filter that hides most rows keeps loading until it fills.
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            page.next.takeIf {
                (shownCount == 0 || last >= shownCount - 5) && !page.loading && !page.failed
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
            val hasFooter = page.loading || page.failed || page.next != null
            LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                item(key = "history-title", contentType = "title") { title?.let { SectionHeader(it) } }
                if (historyOn == false) item(key = "history-off", contentType = "notice") {
                    SectionCard(Modifier.padding(bottom = 12.dp)) { HistoryOff(onOpenSettings) }
                }
                // Only worth offering when messages come from more than one place.
                if (choices.size > 2 || filter != HistoryFilter.All) item(key = "history-filter", contentType = "filter") {
                    SourceFilter(choices, filter, page.rows) { filterToken = it.token }
                }
                if (items.isEmpty() && !hasFooter) item(key = "history-empty-${filter.token}", contentType = "notice") {
                    SectionCard(Modifier.padding(top = 8.dp)) {
                        Quiet(emptyFilterText(filter, page.rows), Modifier.padding(vertical = 16.dp))
                    }
                }
                itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item ->
                    if (item is HistoryItem.Day) "day" else "message"
                }) { index, item ->
                    when (item) {
                        is HistoryItem.Day -> SectionHeader(dayLabel(resources, item.date, now))
                        is HistoryItem.Message -> {
                            val first = items.getOrNull(index - 1) !is HistoryItem.Message
                            val next = items.getOrNull(index + 1)
                            val last = next is HistoryItem.Day || (next == null && !hasFooter)
                            Column(Modifier.cardSegment(first = first, last = last)) {
                                if (!first) RowDivider()
                                val row = item.row
                                val delete = { history.delete(row.summary.id) }
                                SwipeToDelete(onDelete = delete) {
                                    SessionRow(row, time, onClick = { onOpen(row.summary.id) }, onDelete = delete)
                                }
                            }
                        }
                    }
                }
                if (hasFooter) item(key = "history-footer", contentType = "footer") {
                    Box(Modifier.cardSegment(first = items.isEmpty(), last = true), contentAlignment = Alignment.Center) {
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

/** All · WhatsApp · Messages · Chat. */
@Composable
private fun SourceFilter(choices: List<HistoryFilter>, selected: HistoryFilter, rows: List<RecentRow>, onSelect: (HistoryFilter) -> Unit) {
    val label = stringResource(R.string.lab_history_flat_filter)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)
            .semantics { contentDescription = label }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                label = { Text(filterName(choice, rows)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun filterName(filter: HistoryFilter, rows: List<RecentRow>): String = when (filter) {
    HistoryFilter.All -> stringResource(R.string.lab_history_flat_all)
    HistoryFilter.Chat -> stringResource(R.string.lab_chat_origin_chat)
    is HistoryFilter.App -> appName(rows, filter)
}

@Composable
private fun emptyFilterText(filter: HistoryFilter, rows: List<RecentRow>): String = when (filter) {
    is HistoryFilter.App -> stringResource(R.string.lab_history_flat_no_app_messages, appName(rows, filter))
    HistoryFilter.Chat -> stringResource(R.string.lab_history_flat_no_chat_messages)
    // All is never empty here: an empty History shows its own page.
    HistoryFilter.All -> ""
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
