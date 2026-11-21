package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The suggestions page: one section per judgment, rewordings first as they are walked (its
 * count, the text with its changes marked, Accept all; one line once nothing is left in it),
 * then the accepted changes with Undo and Replace. Once every change is accepted, only the
 * final text and that row. Meaning is the card's footer row (see [ResultCard]).
 */
@Composable
internal fun Suggestions(result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions) {
    if (result.looksGood) {
        Outcome(stringResource(R.string.looks_good), stringResource(looksGoodDetail(result.kinds)), success = true)
        // Lab, Leave as written: the clean card's one action, which saves it as written.
        actions.onLeaveAsWritten?.let {
            Spacer(Modifier.height(12.dp))
            LeaveAsWrittenRow(it)
        }
        return
    }

    // Lab, Review flow: one selection walks every change in order (see ReviewQueue.kt).
    // Lab, Type to accept: when on, the open change is accepted by typing it (TypeToAccept.kt).
    val flow = rememberReviewFlow(result, actions.review != null,
        typeToAccept = rememberTypeToAccept(result.revision.original))

    val incomplete = result.withheld.isNotEmpty()
    if (incomplete) {
        val title = when {
            result.withheld.size > 1 -> R.string.changes_withheld
            EditKind.Fix in result.withheld -> R.string.fixes_withheld
            else -> R.string.rewordings_withheld
        }
        Outcome(stringResource(title), stringResource(R.string.withheld_detail))
        LinguizeButton(stringResource(R.string.check_again), actions.onRetry, ButtonKind.Text)
    }

    val review = actions.review
    val revision = result.revision
    // Finished: every change is accepted (or covered by an accepted rewording). The writer
    // needs what will be sent, once, one summary with Undo, and the final action; the
    // per-kind sections would only restate the same text.
    if (review != null && revision.canUndo && result.kinds.all { revision.remaining(it).isEmpty() }) {
        if (incomplete) SectionDivider()
        val copyKind = result.kinds.walkOrder().first()
        AcceptedText(revision, review, canApply = true, onCopy = { actions.onCopy(revision.workingText, copyKind) },
            modifier = flow.finalAction)
        return
    }

    // Empty withheld sections are covered by the notice. Naturalness was judged on
    // the model's corrected text, so it cannot reassure the writer about the original
    // when required corrections were withheld. Sections follow the walk: rewordings first.
    result.kinds.walkOrder().filterNot { kind ->
        revision.edits(kind).isEmpty() &&
            (kind in result.withheld || kind == EditKind.Natural && EditKind.Fix in result.withheld)
    }.forEachIndexed { index, kind ->
        if (incomplete || index > 0) SectionDivider()
        EditSection(kind, result, settled, actions, flow.selection)
    }

    if (review != null && revision.canUndo) {
        SectionDivider()
        AcceptedRow(revision.acceptedCount, review.onUndo, review.onApply, review.applyLabel,
            modifier = flow.finalAction)
    } else if (review != null && flow.allSkipped) {
        // Lab, Leave as written: every change skipped, nothing accepted. Where Replace would be.
        actions.onLeaveAsWritten?.let {
            SectionDivider()
            LeaveAsWrittenRow(it, modifier = flow.finalAction)
        }
    }
}

/** Lab, Leave as written: the card's one filled action when the text stays as written. */
@Composable
internal fun LeaveAsWrittenRow(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        LinguizeButton(stringResource(R.string.lab_leave_as_written), onLeave, ButtonKind.Filled)
    }
}

private fun looksGoodDetail(kinds: List<EditKind>) = when {
    EditKind.Fix in kinds && EditKind.Natural in kinds -> R.string.looks_good_both
    EditKind.Fix in kinds -> R.string.nothing_to_fix
    else -> R.string.sounds_natural
}

/** "3 changes accepted   Undo  [Replace]" (Send in the chat): the card's one filled action. */
@Composable
internal fun AcceptedRow(count: Int, onUndo: () -> Unit, onApply: (() -> Unit)?, applyLabel: Int = R.string.apply,
    modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.changes_accepted_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LinguizeButton(stringResource(R.string.undo), onUndo, ButtonKind.Text)
        onApply?.let { LinguizeButton(stringResource(applyLabel), it, ButtonKind.Filled) }
    }
}

@Composable
private fun EditSection(kind: EditKind, result: CheckResult.Reviewed, settled: List<Settled>, actions: CardActions,
    selection: ReviewSelection? = null) {
    val revision = result.revision
    val review = actions.review
    val fix = kind == EditKind.Fix
    val remaining = revision.remaining(kind)
    if (revision.edits(kind).isEmpty()) {
        SuccessLine(if (fix) stringResource(R.string.nothing_to_fix) else stringResource(R.string.sounds_natural))
        return
    }

    // Partly done: a kind with nothing left to decide is one quiet line. Its accepted changes
    // already show, plain, in the other section's text, so its own would only repeat it.
    if (remaining.isEmpty() && review != null) {
        SuccessLine(resolvedSummary(kind, revision), quiet = true)
        return
    }

    val mark = if (fix) Linguize.colors.proof else Linguize.colors.rewording
    // A fix covered by an accepted rewording is part of the rewording now: not counted here.
    val count = revision.edits(kind).count { !revision.isRetired(it.id) }
    // One change view: rewordings are read on the corrected text (the fixes still on offer
    // applied under them, as Copy takes them), but a fix the writer skipped stays as written.
    val preview = revision.previewOnCorrected(kind, selection?.let { it.skipped - setOfNotNull(it.selected) }.orEmpty())
    val copyText = revision.previewOnCorrected(kind).text
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
        color = if (fix) mark else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(8.dp))
    HighlightedText(
        preview.text, highlights, mark, review?.onAccept,
        onCopy = { actions.onCopy(copyText, kind) },
        copyDescription = if (fix) stringResource(R.string.copy_with_fixes) else stringResource(R.string.copy_with_rewordings),
        onSettle = actions.onSettle,
        dotted = !fix,
        edge = Linguize.colors.proof,
        selection = selection,
    )
    Spacer(Modifier.height(10.dp))
    if (remaining.isEmpty()) {
        SuccessLine(stringResource(R.string.all_accepted), quiet = true)
    } else if (review != null) {
        LinguizeButton(stringResource(R.string.accept_all), { review.onAcceptAll(kind) }, ButtonKind.Tonal)
    }
}

/**
 * A kind with nothing left to decide, in one line: "2 rewordings accepted", "3 fixes
 * accepted", "1 fix covered by a rewording", "2 fixes accepted, 1 covered by a rewording".
 * Covered is History's word for a fix that an accepted rewording already includes.
 */
@Composable
private fun resolvedSummary(kind: EditKind, revision: Revision): String {
    val edits = revision.edits(kind)
    val accepted = edits.count { revision.isApplied(it.id) }
    val covered = edits.count { revision.isRetired(it.id) }
    return when {
        kind == EditKind.Natural -> pluralStringResource(R.plurals.lab_review_rewordings_accepted, accepted, accepted)
        covered == 0 -> pluralStringResource(R.plurals.lab_review_fixes_accepted, accepted, accepted)
        accepted == 0 -> pluralStringResource(R.plurals.lab_review_fixes_covered, covered, covered)
        else -> stringResource(R.string.lab_review_fixes_summary,
            pluralStringResource(R.plurals.lab_review_fixes_accepted, accepted, accepted),
            pluralStringResource(R.plurals.lab_review_covered, covered, covered))
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
internal fun RetainedChanges(revision: Revision?, review: ReviewActions?, canApply: Boolean = false) {
    if (revision == null || !revision.canUndo) return
    SectionDivider()
    AcceptedText(revision, review, canApply)
}

/**
 * What will be sent, once, then "5 changes accepted   Undo  [Replace]" (Send in the chat):
 * the finished card, and the accepted changes kept while a new check runs or after it failed.
 * [onCopy] adds the text's copy button.
 */
@Composable
private fun AcceptedText(revision: Revision, review: ReviewActions?, canApply: Boolean, onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    if (onCopy != null) {
        HighlightedText(revision.workingText, emptyList(), MaterialTheme.colorScheme.onSurface, null,
            onCopy = onCopy, copyDescription = stringResource(R.string.lab_review_copy_text))
    } else {
        Text(revision.workingText, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.height(10.dp))
    if (review != null) {
        AcceptedRow(revision.acceptedCount, review.onUndo, onApply = review.onApply.takeIf { canApply },
            applyLabel = review.applyLabel, modifier = modifier)
    } else {
        Text(
            pluralStringResource(R.plurals.changes_accepted_count, revision.acceptedCount, revision.acceptedCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}
