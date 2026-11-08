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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle

/** The visible list owns deletion feedback; the operation itself stays in the view model. */
@Composable
internal fun HistoryDeletionFeedback(history: HistoryViewModel, snackbar: SnackbarHostState) {
    val pending by history.pendingDelete.collectAsState()
    val failed by history.failedDeletes.collectAsState()
    val failureGeneration by history.failedDeleteGeneration.collectAsState()
    val deletedMessage = stringResource(R.string.check_deleted)
    val undoLabel = stringResource(R.string.undo)
    val incompleteMessage = stringResource(R.string.history_delete_incomplete)
    val retryLabel = stringResource(R.string.retry)

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

    // Undo takes priority. Failed committed work stays hidden and durable, including when
    // storage deleted the row but couldn't finish cleanup. Retry every failed ID so one
    // failure can't keep another from being retried.
    LaunchedEffect(failed, pending, failureGeneration) {
        if (pending != null || failed.isEmpty()) return@LaunchedEffect
        val result = snackbar.showSnackbar(incompleteMessage, actionLabel = retryLabel, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) history.retryFailedDeletes()
    }
}

/**
 * One recorded message, the same for every source: time, where it was written ("WhatsApp",
 * "Chat · Weekend plans"), language; the text; what came of it. The day is in the header above.
 */
@Composable
internal fun SessionRow(row: RecentRow, time: DateTimeFormatter, onClick: () -> Unit, onDelete: () -> Unit) {
    val summary = row.summary
    val resources = LocalResources.current
    val deleteLabel = stringResource(R.string.delete_check)
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete background shows only while swiping.
            .background(Linguize.colors.card)
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
            listOfNotNull(
                time.format(Instant.ofEpochMilli(summary.startedAt).atZone(ZoneId.systemDefault())),
                row.sourceLabel(resources),
                targetLanguageLabel(resources, summary.targetLanguage, summary.requestedLanguage)).joinToString(stringResource(R.string.status_separator)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(firstLine(summary.text), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            summary.statusLine(resources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onError)
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
    LaunchedEffect(state.settledValue) {
        if (state.settledValue == SwipeToDismissBoxValue.EndToStart) {
            // currentValue changes halfway through a drag. Wait for release and
            // the dismissal animation to settle, so resetting cannot interrupt
            // a slow gesture while the finger is still down.
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
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Quiet(stringResource(R.string.history_off), Modifier.weight(1f))
        LinguizeButton(stringResource(R.string.open_settings), onOpenSettings, ButtonKind.Text)
    }
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
