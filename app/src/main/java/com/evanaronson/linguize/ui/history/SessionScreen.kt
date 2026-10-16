package com.evanaronson.linguize.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.ui.card.UnderstandingEntry
import com.evanaronson.linguize.ui.card.UnderstandingPage
import com.evanaronson.linguize.ui.card.understandingEntry
import com.evanaronson.linguize.ui.text.titleResource
import kotlinx.coroutines.launch

/** Exact saved text and decisions; inspecting a past check cannot change it. */
@Composable
fun SessionScreen(session: SessionViewModel, onDeleted: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val deletion = session.deletion
    val page = session.page
    LaunchedEffect(deletion) {
        if (deletion == SessionDeletion.Deleted) onDeleted()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (page) {
            SessionPage.Loading -> Unit
            SessionPage.Missing -> Faint(stringResource(R.string.check_was_deleted))
            SessionPage.Failed -> Column(Modifier.padding(vertical = 12.dp)) {
                Faint(stringResource(R.string.history_load_failed))
                TextButton(onClick = session::retry) { Text(stringResource(R.string.retry)) }
            }
            is SessionPage.Shown -> Shown(
                page,
                onMeaningNavigation = { scope.launch { scroll.scrollTo(0) } },
            )
        }
        if (deletion == SessionDeletion.Failed) {
            Text(stringResource(R.string.delete_check_failed), color = MaterialTheme.colorScheme.error)
        }
        // A refresh may remove the row before a failed checkpoint has been retried.
        if (page is SessionPage.Shown || deletion == SessionDeletion.Failed) {
            DeleteButton(session::delete, deleting = deletion == SessionDeletion.Deleting)
        }
    }
}

@Composable
private fun Shown(
    page: SessionPage.Shown,
    onMeaningNavigation: () -> Unit,
) {
    val session = page.session
    SessionDetails(session, page.appLabel)

    val reviewed = page.card?.result as? CheckResult.Reviewed
    val settled = page.card?.settled.orEmpty()
    var meaningOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    val showMeaning: (Boolean) -> Unit = {
        meaningOpen = it
        onMeaningNavigation()
    }
    BackHandler(enabled = meaningOpen) { showMeaning(false) }
    if (meaningOpen && reviewed != null) {
        UnderstandingPage(reviewed.meaning, reviewed.assumptions, settled,
            onSettle = null, onRemoveChoice = null, onBack = { showMeaning(false) })
    } else {
        TextBlock(stringResource(R.string.history_before), session.text)
        WhatCameOfIt(session)
        page.failure?.let {
            Text(stringResource(R.string.check_failed_reason, stringResource(it.titleResource)),
                color = MaterialTheme.colorScheme.error)
        }
        when (page.warning) {
            Verdict.Status.Unclear -> Faint(stringResource(R.string.meaning_unclear))
            Verdict.Status.WrongLanguage -> Faint(stringResource(R.string.wrong_language_status))
            else -> Unit
        }
        if (reviewed != null) {
            UnderstandingEntry(understandingEntry(reviewed.meaning, reviewed.assumptions,
                settled, readOnly = true), onOpen = { showMeaning(true) })
        }
        SavedSuggestions(session.id, page.suggestions, hasFinalText = session.finalText != null,
            reviewed = reviewed, settled = settled)
    }
}

@Composable
private fun SessionDetails(session: SessionRecord, appLabel: String?) {
    val time = rememberTimeFormat()
    val resources = LocalResources.current
    Column {
        Text(relativeDate(resources, session.startedAt, System.currentTimeMillis(), time, alwaysTime = true), style = MaterialTheme.typography.titleLarge)
        appLabel?.let { Faint(it) }
        Faint(stringResource(R.string.history_target_language,
            targetLanguageLabel(resources, session.targetLanguage, session.requestedLanguage)))
        session.detectedLanguage?.takeIf { it != session.targetLanguage }?.let {
            Faint(stringResource(R.string.history_detected_language, detectedLanguageLabel(resources, it)))
        }
    }
}

@Composable
private fun WhatCameOfIt(session: SessionRecord) {
    val finalText = session.finalText
    if (finalText != null) {
        TextBlock(stringResource(R.string.history_after), finalText)
        return
    }
    when (session.outcome) {
        Outcome.Copied -> stringResource(R.string.history_copy_only)
        Outcome.None -> stringResource(R.string.outcome_closed)
        Outcome.Abandoned -> stringResource(R.string.outcome_abandoned)
        Outcome.Applied, Outcome.Failed, null -> stringResource(R.string.history_no_applied_result)
    }.let { Faint(it) }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit, deleting: Boolean) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { confirming = true },
        enabled = !deleting,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(if (deleting) R.string.deleting_check else R.string.delete)) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_check_question)) },
            text = { Text(stringResource(R.string.delete_check_description)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A labelled text, set apart the way the card shows the meaning. */
@Composable
private fun TextBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun Faint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
