package com.evanaronson.linguize.ui.card

import android.content.Context
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.ui.copyToClipboard

/** What the card's buttons do; supplied by the screen hosting it. */
class CardActions(
    /** Copies the version shown for a kind: its text, and which kind it was. */
    val onCopy: (text: String, kind: EditKind) -> Unit,
    /** Null when the selected text can't be replaced; the card then offers only Copy. */
    val review: ReviewActions?,
    val onRetry: () -> Unit,
    /** In-app recovery for missing credentials; popups use their branded app button. */
    val onOpenSettings: (() -> Unit)?,
    /** Overrides an assumption (by its "about") with the writer's answer and checks again. */
    val onSettle: (about: String, answer: String) -> Unit,
    /** True for a past check: the assumption chips would do nothing, so the card hides them. */
    val readOnly: Boolean = false,
) {
    companion object {
        /**
         * The same behaviour wherever the card is shown: Copy copies the version on
         * screen and closes without replacement. Accept and Accept all keep the card
         * open; Done and dismissal
         * ([onClose]) hand back accepted changes.
         */
        fun of(
            check: CheckViewModel,
            context: Context,
            onClose: () -> Unit,
            onOpenSettings: (() -> Unit)?,
            canReplace: Boolean = true,
            /** Closes after Copy without putting accepted changes back into the source. */
            onCopyClose: () -> Unit,
        ) = CardActions(
            onCopy = { text, kind ->
                context.copyToClipboard(text)
                check.copied(kind)
                onCopyClose()
            },
            review = if (canReplace) {
                ReviewActions(
                    onAccept = check::accept,
                    onAcceptAll = { check.acceptAll(it) },
                    onUndo = check::undo,
                    onDone = onClose,
                )
            } else {
                null
            },
            onRetry = check::recheck,
            onOpenSettings = onOpenSettings,
            onSettle = check::settle,
        )

        /**
         * For a card that's only read, like a past check: Copy copies and nothing else does
         * anything. No check behind it, so it can't reach the model or history.
         */
        fun readOnly(context: Context) = CardActions(
            onCopy = { text, _ -> context.copyToClipboard(text) },
            review = null,
            onRetry = {},
            onOpenSettings = null,
            onSettle = { _, _ -> },
            readOnly = true,
        )
    }
}

class ReviewActions(
    val onAccept: (editId: Int) -> Unit,
    val onAcceptAll: (EditKind) -> Unit,
    val onUndo: () -> Unit,
    /** Closes the card, handing back the accepted changes. */
    val onDone: () -> Unit,
)
