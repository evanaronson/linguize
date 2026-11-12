package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import kotlinx.coroutines.delay

/** Lab, Review flow: what the suggestions page needs from the shared selection. */
internal class ReviewFlowUi(
    /** Passed to every section's text. */
    val selection: ReviewSelection,
    /** On the card's last row, so it scrolls into view once every change is decided. */
    val finalAction: Modifier,
)

/**
 * One selection for the whole result, kept while the same check is shown (a new answer
 * starts again at its first change). It opens on the first change, follows each Accept
 * to the next change, goes back to a change Undo brings back, and clears once nothing is
 * left, bringing Replace (Send in the chat) into view.
 */
@Composable
internal fun rememberReviewFlow(result: CheckResult.Reviewed, canAccept: Boolean): ReviewFlowUi {
    val revision = result.revision
    val flow = remember(revision.original, revision.edits, result.kinds) { ReviewFlow(result.kinds, revision) }
    val focus = flow.focus(revision)
    val position = focus.position(revision, result.kinds)?.let { (index, total) ->
        stringResource(R.string.lab_review_flow_position, index, total)
    }
    val done = focus.isDone(revision, result.kinds) && revision.canUndo
    val finalAction = remember { BringIntoViewRequester() }
    LaunchedEffect(done) {
        if (done) {
            // After the last explanation has folded away.
            delay(FOLD_MILLIS)
            finalAction.bringIntoView()
        }
    }
    return ReviewFlowUi(
        ReviewSelection(focus.selected, flow::select, if (canAccept) flow::skip else null, position),
        Modifier.bringIntoViewRequester(finalAction),
    )
}

private const val FOLD_MILLIS = 320L
