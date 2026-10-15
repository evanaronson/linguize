package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled

/** The way into the meaning and assumptions, one section per judgment, then Undo and Done once something has been accepted. */
@Composable
internal fun ReviewContent(result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions) {
    // How the text was understood is a page of its own: in from the right, back to the left.
    var showingUnderstanding by rememberSaveable(result, settled) { mutableStateOf(false) }
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = showingUnderstanding,
        transitionSpec = {
            val direction = if (targetState) forward else -forward
            (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { -direction * it / 3 } + fadeOut())
        },
        label = "understanding",
    ) { understandingPage ->
        Column {
            if (understandingPage) {
                UnderstandingPage(
                    result.meaning,
                    result.assumptions,
                    settled,
                    actions.onSettle.takeUnless { actions.readOnly },
                    onBack = { showingUnderstanding = false },
                )
            } else {
                val count = settled.size + result.assumptions.count { a -> settled.none { it.about == a.about } }
                UnderstandingEntry(result.meaning, count, onOpen = { showingUnderstanding = true })
                if (count > 0 || result.meaning.isNotBlank()) Spacer(Modifier.height(8.dp))
                Suggestions(result, actions)
            }
        }
    }
}

@Composable
private fun Suggestions(result: CheckResult.Reviewed, actions: CardActions) {
    if (result.looksGood) {
        val checked = result.kinds.map { if (it == EditKind.Fix) stringResource(R.string.nothing_to_fix) else stringResource(R.string.sounds_natural) }
        Outcome(Mark.Good, stringResource(R.string.looks_good), checked.joinToString(stringResource(R.string.status_separator)))
        return
    }

    val review = actions.review
    result.kinds.forEachIndexed { index, kind ->
        if (index > 0) SectionDivider()
        EditSection(kind, result.revision, kind in result.withheld, actions.onCopy, review)
    }

    if (review != null && result.revision.canUndo) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = result.revision.acceptedCount
            Text(
                pluralStringResource(R.plurals.changes_accepted_count, count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = review.onUndo) { Text(stringResource(R.string.undo)) }
            FilledTonalButton(onClick = review.onApply) { Text(stringResource(R.string.apply)) }
        }
    }
}

@Composable
private fun EditSection(kind: EditKind, revision: Revision, withheld: Boolean, onCopy: (String, EditKind) -> Unit, review: ReviewActions?) {
    val fix = kind == EditKind.Fix
    val remaining = revision.remaining(kind)
    if (withheld) {
        StatusLine(Mark.Unsure, stringResource(if (fix) R.string.fixes_withheld else R.string.rewordings_withheld))
        if (revision.edits(kind).isEmpty()) return
        Spacer(Modifier.height(10.dp))
    }
    if (revision.edits(kind).isEmpty()) {
        StatusLine(Mark.Good, if (fix) stringResource(R.string.nothing_to_fix) else stringResource(R.string.sounds_natural))
        return
    }

    val accent = if (fix) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val count = remaining.size.takeIf { it > 0 } ?: revision.edits(kind).size
    val label = pluralStringResource(
        if (fix) R.plurals.fix_count else R.plurals.rewording_count,
        count, count,
    )
    val preview = revision.preview(kind)
    val highlights = remaining.mapNotNull { edit ->
        preview.ranges[edit.id]?.let { Highlight(edit.id, it, edit.from, edit.why) }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = accent,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(6.dp))
    HighlightedText(
        preview.text, highlights, accent, review?.onAccept,
        onCopy = { onCopy(preview.text, kind) },
        copyDescription = if (fix) stringResource(R.string.copy_with_fixes) else stringResource(R.string.copy_with_rewordings),
    )
    if (remaining.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        StatusLine(Mark.Good, if (fix) stringResource(R.string.fixes_accepted) else stringResource(R.string.rewordings_accepted))
    } else if (review != null) {
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = { review.onAcceptAll(kind) }) { Text(stringResource(R.string.accept_all)) }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(14.dp))
}
