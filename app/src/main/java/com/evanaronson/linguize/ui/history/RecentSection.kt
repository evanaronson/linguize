package com.evanaronson.linguize.ui.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.ui.components.uiLocale
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle

/**
 * The checks made before, newest first. Tap one to open it; swipe it away to delete
 * it, with a moment to undo. [historyOn] is null until the setting has been read.
 */
@Composable
fun RecentSection(
    history: HistoryViewModel,
    historyOn: Boolean?,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onViewAll: () -> Unit,
) {
    val rows by history.recent.collectAsState()
    val time = rememberTimeFormat()
    HistoryUndo(history, snackbar)

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onViewAll) { Text(stringResource(R.string.view_all_history)) }
    }
    if (historyOn == false) HistoryOff(onOpenSettings)
    val shown = rows ?: return
    if (shown.isEmpty()) {
        if (historyOn == true) Quiet(stringResource(R.string.recent_empty))
        return
    }
    val now = System.currentTimeMillis()
    Column {
        shown.take(HistoryViewModel.PREVIEW_SIZE).forEachIndexed { index, row ->
            key(row.summary.id) {
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val delete = { history.delete(row.summary.id) }
                SwipeToDelete(onDelete = delete) {
                    SessionRow(row, now, time, onClick = { onOpen(row.summary.id) }, onDelete = delete)
                }
            }
        }
    }
}

/** The visible list owns the Undo snackbar; the operation itself stays in the view model. */
@Composable
internal fun HistoryUndo(history: HistoryViewModel, snackbar: SnackbarHostState) {
    val pending by history.pendingDelete.collectAsState()
    val deletedMessage = stringResource(R.string.check_deleted)
    val undoLabel = stringResource(R.string.undo)

    // Undo for the row just deleted. The deletion itself waits in the view model: here it's
    // committed only when the snackbar goes unanswered. If the snackbar is torn down instead
    // (rotation, dark mode, another screen), the view model gives it a moment to come back.
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        history.undoShown(id)
        var answered = false
        try {
            val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
            answered = true
            if (result == SnackbarResult.ActionPerformed) history.undoDelete(id) else history.commitDelete(id)
        } finally {
            if (!answered) history.undoHidden(id)
        }
    }
}

@Composable
internal fun SessionRow(row: RecentRow, now: Long, time: DateTimeFormatter, onClick: () -> Unit, onDelete: () -> Unit) {
    val summary = row.summary
    val resources = LocalResources.current
    val deleteLabel = stringResource(R.string.delete_check)
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete background shows only while swiping.
            .background(MaterialTheme.colorScheme.surface)
            // On the same node as the click, so screen readers offer Delete on the row they're on.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) {
                        onDelete()
                        true
                    },
                )
            }
            .clickable(onClickLabel = stringResource(R.string.open_check), onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            listOfNotNull(relativeDate(resources, summary.startedAt, now, time), row.appLabel, targetLanguageLabel(resources, summary.targetLanguage, summary.requestedLanguage)).joinToString(stringResource(R.string.status_separator)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(firstLine(summary.text), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            summary.statusLine(resources),
            style = MaterialTheme.typography.bodySmall,
            color = if (summary.outcome == Outcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Swipe toward the start to delete; screen readers get the row's Delete action instead (see [SessionRow]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberDeleteSwipeState(onDelete)
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberDeleteSwipeState(onDelete: () -> Unit): SwipeToDismissBoxState {
    val delete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            // Lazy rows retain saveable state when removed. Undo must restore a settled
            // row, rather than restoring this dismissal and deleting the row again.
            state.snapTo(SwipeToDismissBoxValue.Settled)
            delete()
        }
    }
    return state
}

@Composable
internal fun HistoryOff(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Quiet(stringResource(R.string.history_off), Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
    }
}

@Composable
internal fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** "14:02" or "2:02 PM", as the phone is set. */
@Composable
internal fun rememberTimeFormat(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalResources.current.uiLocale
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)
            .withDecimalStyle(DecimalStyle.of(locale))
    }
}
