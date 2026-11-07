package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * The suggestions page: one section per judgment (its count, the text with its changes
 * marked, Accept all), then the accepted changes with Undo and Apply. Meaning is the
 * card's footer row (see [ResultCard]).
 */
@Composable
internal fun Suggestions(result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions) {
    if (result.looksGood) {
        Outcome(stringResource(R.string.looks_good), stringResource(looksGoodDetail(result.kinds)))
        return
    }

    // Naturalness was judged on the model's corrected text. It cannot reassure
    // the writer about the original when required corrections were withheld.
    result.kinds.filterNot { it == EditKind.Natural && EditKind.Fix in result.withheld &&
        it !in result.withheld && result.revision.edits(it).isEmpty()
    }.forEachIndexed { index, kind ->
        if (index > 0) SectionDivider()
        EditSection(kind, result, settled, actions)
    }

    if (result.withheld.isNotEmpty()) {
        LinguizeButton(stringResource(R.string.retry), actions.onRetry, ButtonKind.Text)
    }

    val review = actions.review
    if (review != null && result.revision.canUndo) {
        SectionDivider()
        AcceptedRow(result.revision.acceptedCount, review.onUndo, review.onApply)
    }
}

private fun looksGoodDetail(kinds: List<EditKind>) = when {
    EditKind.Fix in kinds && EditKind.Natural in kinds -> R.string.looks_good_both
    EditKind.Fix in kinds -> R.string.nothing_to_fix
    else -> R.string.sounds_natural
}

/** "3 changes accepted   Undo  [Apply]": Apply is the card's one filled action. */
@Composable
internal fun AcceptedRow(count: Int, onUndo: () -> Unit, onApply: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.changes_accepted_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LinguizeButton(stringResource(R.string.undo), onUndo, ButtonKind.Text)
        onApply?.let { LinguizeButton(stringResource(R.string.apply), it, ButtonKind.Filled) }
    }
}

@Composable
private fun EditSection(kind: EditKind, result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions) {
    val revision = result.revision
    val withheld = kind in result.withheld
    val review = actions.review
    val fix = kind == EditKind.Fix
    val remaining = revision.remaining(kind)
    if (withheld) {
        StatusLine(stringResource(if (fix) R.string.fixes_withheld else R.string.rewordings_withheld))
        if (revision.edits(kind).isEmpty()) return
        Spacer(Modifier.height(10.dp))
    }
    if (revision.edits(kind).isEmpty()) {
        StatusLine(if (fix) stringResource(R.string.nothing_to_fix) else stringResource(R.string.sounds_natural))
        return
    }

    val mark = if (fix) Linguize.colors.proof else Linguize.colors.rewording
    val count = revision.edits(kind).size
    val preview = revision.previewOnCorrected(kind)
    val highlights = remaining.mapNotNull { edit ->
        preview.ranges[edit.id]?.let {
            Highlight(edit.id, it, edit.from, edit.why, edit.assessment,
                linkedAssumptions(edit.assumptionIndices, result.assumptions, settled),
                linkedAnswers(edit.assumptionIndices, result.assumptions, settled))
        }
    }
    Text(
        pluralStringResource(if (fix) R.plurals.fix_count else R.plurals.rewording_count, count, count),
        style = MaterialTheme.typography.labelLarge,
        color = mark,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(8.dp))
    HighlightedText(
        preview.text, highlights, mark, review?.onAccept,
        onCopy = { actions.onCopy(preview.text, kind) },
        copyDescription = if (fix) stringResource(R.string.copy_with_fixes) else stringResource(R.string.copy_with_rewordings),
        onSettle = actions.onSettle,
        dotted = !fix,
        edge = Linguize.colors.proof,
    )
    Spacer(Modifier.height(10.dp))
    if (remaining.isEmpty()) {
        DoneLine(stringResource(R.string.all_accepted))
    } else if (review != null) {
        LinguizeButton(stringResource(R.string.accept_all), { review.onAcceptAll(kind) }, ButtonKind.Tonal)
    }
}

/** 14dp, a hairline, 14dp: between a card's sections. */
@Composable
internal fun SectionDivider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(14.dp))
}

/** Accepted text stays inspectable and undoable without offering the old suggestions. */
@Composable
internal fun RetainedChanges(revision: Revision?, review: ReviewActions?) {
    if (revision == null || !revision.canUndo) return
    SectionDivider()
    if (review != null) AcceptedRow(revision.acceptedCount, review.onUndo, onApply = null)
    else Text(
        pluralStringResource(R.plurals.changes_accepted_count, revision.acceptedCount, revision.acceptedCount),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(revision.workingText, style = MaterialTheme.typography.bodyLarge)
}
