package com.evanaronson.linguize.ui.history

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.text.titleResource

/**
 * One past check: when and where, the card as it was (read-only), what went back to
 * the app, and Delete.
 */
@Composable
fun SessionScreen(session: SessionViewModel, onDeleted: () -> Unit) {
    val deletion = session.deletion
    LaunchedEffect(deletion) {
        if (deletion == SessionDeletion.Deleted) onDeleted()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val page = session.page) {
            SessionPage.Loading -> Unit
            SessionPage.Missing -> Faint(stringResource(R.string.check_was_deleted))
            is SessionPage.Shown -> Shown(
                page,
                deletion = deletion,
                onDelete = session::delete,
            )
        }
    }
}

@Composable
private fun Shown(page: SessionPage.Shown, deletion: SessionDeletion, onDelete: () -> Unit) {
    val session = page.session
    SessionDetails(session, page.appLabel)

    val card = page.card
    // The card shows the text itself only when it has suggestions to show in it.
    val cardShowsText = (card?.result as? CheckResult.Reviewed)?.looksGood == false
    if (!cardShowsText) TextBlock(stringResource(R.string.your_text), session.text)
    if (card != null) {
        ReadOnlyCard(card)
    } else {
        Text(
            page.failure?.let { stringResource(R.string.check_failed_reason, stringResource(it.titleResource)) } ?: stringResource(R.string.no_result),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    WhatCameOfIt(session)
    if (deletion == SessionDeletion.Failed) {
        Text(stringResource(R.string.delete_check_failed), color = MaterialTheme.colorScheme.error)
    }
    DeleteButton(onDelete, deleting = deletion == SessionDeletion.Deleting)
}

@Composable
private fun SessionDetails(session: SessionRecord, appLabel: String?) {
    val time = rememberTimeFormat()
    val resources = LocalResources.current
    Column {
        Text(relativeDate(resources, session.startedAt, System.currentTimeMillis(), time, alwaysTime = true), style = MaterialTheme.typography.titleLarge)
        appLabel?.let { Faint(it) }
    }
}

/**
 * The card's own content, with nothing to act on: no Accept, and assumptions can't be
 * changed. Copy only copies; no check stands behind the card, so it can't reach the
 * model or history.
 */
@Composable
private fun ReadOnlyCard(card: CardState.Done) {
    val context = LocalContext.current
    val actions = remember(context) { CardActions.readOnly(context) }
    ResultCard(
        state = card,
        actions = actions,
        modifier = Modifier.fillMaxWidth(),
        scrollable = false,
    )
}

@Composable
private fun WhatCameOfIt(session: SessionRecord) {
    val finalText = session.finalText
    if (finalText != null) {
        TextBlock(stringResource(R.string.put_back_in_app), finalText)
        return
    }
    when (session.outcome) {
        Outcome.Copied -> stringResource(R.string.outcome_copied)
        Outcome.None -> stringResource(R.string.outcome_closed)
        Outcome.Abandoned -> stringResource(R.string.outcome_abandoned)
        Outcome.Applied, Outcome.Failed, null -> null
    }?.let { Faint(it) }
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
