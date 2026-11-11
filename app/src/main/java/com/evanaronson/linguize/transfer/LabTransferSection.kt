package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader

/**
 * Settings: "Bring in data from Linguize X Claude", only in an app other than Linguize X Claude
 * and only while it is installed. Asks first, then shows what came in. [onImported] lets
 * Settings show values that were filled in.
 */
@Composable
fun LabTransferSection(onImported: () -> Unit = {}) {
    val context = LocalContext.current
    val offered = remember(context) { LabTransfer.offered(context) }
    if (!offered) return
    val state by LabTransfer.state.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val running = state == LabTransfer.State.Running

    SectionHeader(stringResource(R.string.lab_transfer_header))
    ListCard({
        ListRow(
            stringResource(R.string.lab_transfer_row),
            stringResource(if (running) R.string.lab_transfer_row_running else R.string.lab_transfer_row_summary),
            onClick = if (running) null else ({ confirming = true }),
        )
    })

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.lab_transfer_confirm_title)) },
            text = { Text(stringResource(R.string.lab_transfer_confirm_text)) },
            confirmButton = {
                LinguizeButton(stringResource(R.string.lab_transfer_confirm), onClick = {
                    confirming = false
                    LabTransfer.start(App.of(context), onImported)
                }, kind = ButtonKind.Filled)
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    when (val current = state) {
        is LabTransfer.State.Done -> ResultDialog(stringResource(R.string.lab_transfer_done_title)) { ReportLines(current.report) }
        LabTransfer.State.Unavailable -> ResultDialog(stringResource(R.string.lab_transfer_unavailable_title)) {
            Text(stringResource(R.string.lab_transfer_unavailable_text))
        }
        LabTransfer.State.Failed -> ResultDialog(stringResource(R.string.lab_transfer_failed_title)) {
            Text(stringResource(R.string.lab_transfer_failed_text))
        }
        LabTransfer.State.Idle, LabTransfer.State.Running -> Unit
    }
}

@Composable
private fun ResultDialog(title: String, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = LabTransfer::dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        },
        confirmButton = { TextButton(onClick = LabTransfer::dismiss) { Text(stringResource(R.string.lab_transfer_close)) } },
    )
}

/** Counts first, then what was here already, what stayed behind and what failed. */
@Composable
private fun ReportLines(report: TransferReport) {
    if (!report.addedAnything) Text(stringResource(R.string.lab_transfer_nothing_new))
    for ((kind, count) in report.added) {
        val plural = kind.plural
        Text(if (plural != null) pluralStringResource(plural, count, count) else stringResource(R.string.lab_transfer_patterns))
    }
    if (report.alreadyHere > 0) Text(pluralStringResource(R.plurals.lab_transfer_already_here, report.alreadyHere, report.alreadyHere))
    for ((reason, count) in report.skipped) {
        Text(when (reason) {
            TransferSkip.HistoryOff -> stringResource(R.string.lab_transfer_skip_history_off)
            TransferSkip.HistoryVersion -> stringResource(R.string.lab_transfer_skip_history_version)
            TransferSkip.UnfinishedChecks -> pluralStringResource(R.plurals.lab_transfer_skip_unfinished, count, count)
            TransferSkip.DeletedChecks -> pluralStringResource(R.plurals.lab_transfer_skip_deleted, count, count)
            TransferSkip.ChatsDeletedHere -> pluralStringResource(R.plurals.lab_transfer_skip_chats_deleted, count, count)
            TransferSkip.LessonsFull -> pluralStringResource(R.plurals.lab_transfer_skip_lessons_full, count, count)
            TransferSkip.PatternsWithoutHistory -> stringResource(R.string.lab_transfer_skip_patterns)
        })
    }
    if (report.failed.isNotEmpty()) {
        val names = report.failed.map { it.partName }.distinct().map { stringResource(it) }
        Text(stringResource(R.string.lab_transfer_partly_failed, names.joinToString(stringResource(R.string.list_separator))))
    }
    Text(stringResource(R.string.lab_transfer_keys))
}

private val TransferKind.plural: Int?
    get() = when (this) {
        TransferKind.Checks -> R.plurals.lab_transfer_checks
        TransferKind.Chats -> R.plurals.lab_transfer_chats
        TransferKind.Messages -> R.plurals.lab_transfer_messages
        TransferKind.Time -> R.plurals.lab_transfer_time
        TransferKind.Notes -> R.plurals.lab_transfer_notes
        TransferKind.AboutYou -> R.plurals.lab_transfer_about_you
        TransferKind.Phrases -> R.plurals.lab_transfer_phrases
        TransferKind.Lessons -> R.plurals.lab_transfer_lessons
        TransferKind.Decks -> R.plurals.lab_transfer_decks
        TransferKind.Languages -> R.plurals.lab_transfer_languages
        TransferKind.AppVoice -> R.plurals.lab_transfer_app_voice
        TransferKind.WritingProfiles -> R.plurals.lab_transfer_writing_profiles
        TransferKind.Patterns -> null
        TransferKind.Preferences -> R.plurals.lab_transfer_preferences
    }

@get:StringRes
private val TransferKind.partName: Int
    get() = when (this) {
        TransferKind.Checks -> R.string.lab_transfer_part_history
        TransferKind.Chats, TransferKind.Messages -> R.string.lab_transfer_part_chat
        TransferKind.Time -> R.string.lab_transfer_part_time
        TransferKind.Notes -> R.string.lab_transfer_part_notes
        TransferKind.AboutYou -> R.string.lab_transfer_part_memory
        TransferKind.Phrases -> R.string.lab_transfer_part_phrasebook
        TransferKind.Lessons -> R.string.lab_transfer_part_lessons
        TransferKind.Decks -> R.string.lab_transfer_part_decks
        TransferKind.Languages -> R.string.lab_transfer_part_languages
        TransferKind.AppVoice -> R.string.lab_transfer_part_app_voice
        TransferKind.WritingProfiles -> R.string.lab_transfer_part_writing_profiles
        TransferKind.Patterns -> R.string.lab_transfer_part_patterns
        TransferKind.Preferences -> R.string.lab_transfer_part_settings
    }
