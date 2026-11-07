package com.evanaronson.linguize.ui.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.ui.card.AssumptionList
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.DISABLED_ALPHA
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.text.titleResource

private val BlockPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Exact saved text and decisions; inspecting a past check cannot change it. */
@Composable
fun SessionScreen(session: SessionViewModel, onDeleted: () -> Unit, onOpenAssumptions: () -> Unit = {}) {
    val deletion = session.deletion
    val page = session.page
    LaunchedEffect(deletion) {
        if (deletion == SessionDeletion.Deleted) onDeleted()
    }
    ScreenColumn {
        when (page) {
            SessionPage.Loading -> Unit
            SessionPage.Missing -> CardNote(stringResource(R.string.check_was_deleted))
            SessionPage.Failed -> {
                CardNote(stringResource(R.string.history_load_failed))
                LinguizeButton(stringResource(R.string.retry), session::retry, ButtonKind.Text,
                    modifier = Modifier.padding(start = 8.dp))
            }
            is SessionPage.Shown -> Shown(page, onOpenAssumptions)
        }
        if (deletion == SessionDeletion.Failed) {
            CardNote(stringResource(R.string.delete_check_failed), color = MaterialTheme.colorScheme.error)
        }
        // A refresh may remove the row before a failed checkpoint has been retried.
        if (page is SessionPage.Shown || deletion == SessionDeletion.Failed) {
            Spacer(Modifier.height(16.dp))
            DeleteRow(session::delete, deleting = deletion == SessionDeletion.Deleting)
        }
    }
}

@Composable
private fun Shown(page: SessionPage.Shown, onOpenAssumptions: () -> Unit) {
    val session = page.session
    val reviewed = page.card?.result as? CheckResult.Reviewed
    val settled = page.card?.settled.orEmpty()
    SessionDetails(session, page.appLabel)

    SectionHeader(stringResource(R.string.history_before))
    SectionCard(contentPadding = BlockPadding) {
        TextBlock(session.text)
        page.failure?.let {
            Quiet(stringResource(R.string.check_failed_reason, stringResource(it.titleResource)),
                Modifier.padding(top = 12.dp))
        }
        when (page.warning) {
            Verdict.Status.Unclear -> Quiet(stringResource(R.string.meaning_unclear), Modifier.padding(top = 12.dp))
            Verdict.Status.WrongLanguage -> Quiet(stringResource(R.string.wrong_language_status), Modifier.padding(top = 12.dp))
            else -> Unit
        }
    }

    if (reviewed != null && (reviewed.meaning.isNotBlank() || reviewed.assumptions.isNotEmpty() || settled.isNotEmpty())) {
        SectionHeader(stringResource(R.string.meaning))
        SectionCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
            if (reviewed.meaning.isNotBlank()) TextBlock(reviewed.meaning, Modifier.padding(bottom = 12.dp))
            val readings = reviewed.assumptions.size + settled.count { !it.isMeaning }
            if (readings > 0) {
                val first = settled.firstOrNull { !it.isMeaning }?.let { it.about to it.answer }
                    ?: reviewed.assumptions.first().let { it.about to it.assumed }
                ListRow(
                    stringResource(R.string.assumptions_count, readings),
                    stringResource(R.string.assumption_summary, first.first, first.second.lowercaseFirst()),
                    onClick = onOpenAssumptions,
                )
            }
            settled.firstOrNull { it.isMeaning }?.let {
                if (readings > 0) RowDivider()
                ListRow(stringResource(R.string.your_clarification), it.answer)
            }
        }
    }

    SectionHeader(stringResource(R.string.history_after))
    SectionCard(contentPadding = BlockPadding) { WhatCameOfIt(session) }

    SavedSuggestions(session.id, page.suggestions, hasFinalText = session.finalText != null,
        reviewed = reviewed, settled = settled)
}

/** The readings a past check's suggestions rested on, and the writer's answers, read-only. */
@Composable
fun SessionAssumptionsScreen(session: SessionViewModel) {
    val page = session.page as? SessionPage.Shown ?: return
    val reviewed = page.card?.result as? CheckResult.Reviewed ?: return
    val settled = page.card?.settled.orEmpty().filterNot { it.isMeaning }
    val open = reviewed.assumptions.filterNot { a -> settled.any { it.answers(a.about) } }
    ScreenColumn {
        SectionHeader(stringResource(R.string.assumptions))
        SectionCard(contentPadding = BlockPadding) {
            Quiet(stringResource(R.string.assumptions_description_past))
            Spacer(Modifier.height(4.dp))
            AssumptionList(reviewed.assumptions, open, settled, onSettle = null, onRemoveChoice = null)
        }
    }
}

@Composable
private fun SessionDetails(session: SessionRecord, appLabel: String?) {
    val time = rememberTimeFormat()
    val resources = LocalResources.current
    SectionHeader(relativeDate(resources, session.startedAt, System.currentTimeMillis(), time, alwaysTime = true))
    val target = stringResource(R.string.history_target_language,
        targetLanguageLabel(resources, session.targetLanguage, session.requestedLanguage))
    val detected = session.detectedLanguage?.takeIf { it != session.targetLanguage }?.let {
        stringResource(R.string.history_detected_language, detectedLanguageLabel(resources, it))
    }
    val details = listOfNotNull(target, detected).joinToString("\n")
    ListCard({ ListRow(appLabel ?: details, if (appLabel != null) details else null) })
}

@Composable
private fun WhatCameOfIt(session: SessionRecord) {
    val finalText = session.finalText
    if (finalText != null) {
        TextBlock(finalText)
        return
    }
    when (session.outcome) {
        Outcome.Copied -> stringResource(R.string.history_copy_only)
        Outcome.None -> stringResource(R.string.outcome_closed)
        Outcome.Abandoned -> stringResource(R.string.outcome_abandoned)
        Outcome.Applied, Outcome.Failed, null -> stringResource(R.string.history_no_applied_result)
    }.let { Quiet(it) }
}

@Composable
private fun DeleteRow(onDelete: () -> Unit, deleting: Boolean) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ListCard({
        ListRow(
            stringResource(if (deleting) R.string.deleting_check else R.string.delete_this_check),
            stringResource(R.string.delete_this_check_summary),
            modifier = if (deleting) Modifier.alpha(DISABLED_ALPHA) else Modifier,
            onClick = if (deleting) null else ({ confirming = true }),
            onClickLabel = stringResource(R.string.delete),
        )
    })
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

/** "The meeting is tomorrow" after a colon: lower-case its first letter, unless it starts a name or acronym. */
private fun String.lowercaseFirst(): String =
    if (length > 1 && this[0].isUpperCase() && this[1].isLowerCase()) replaceFirstChar { it.lowercaseChar() } else this
