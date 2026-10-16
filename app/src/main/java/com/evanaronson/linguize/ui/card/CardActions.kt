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
    /** Opens settings for configuration recovery; hosts own navigation and popup return. */
    val onOpenSettings: (() -> Unit)?,
    /** Overrides an assumption (by its "about") with the writer's answer and checks again. */
    val onSettle: (about: String, answer: String) -> Unit,
    /** Retracts one live answer and checks again; null for a past check. */
    val onRemoveChoice: ((about: String) -> Unit)? = null,
) {
    companion object {
        /**
         * The same behaviour wherever the card is shown: Copy copies the version on
         * screen and keeps the card open. Accept and Accept all keep the card
         * open too; Apply and dismissal
         * ([onClose]) hand back accepted changes.
         */
        fun of(
            check: CheckViewModel,
            context: Context,
            onClose: () -> Unit,
            onOpenSettings: (() -> Unit)?,
            canReplace: Boolean = true,
        ) = CardActions(
            onCopy = { text, kind ->
                context.copyToClipboard(text)
                check.copied(kind)
            },
            review = if (canReplace) {
                ReviewActions(
                    onAccept = check::accept,
                    onAcceptAll = { check.acceptAll(it) },
                    onUndo = check::undo,
                    onApply = onClose,
                )
            } else {
                null
            },
            onRetry = check::recheck,
            onOpenSettings = onOpenSettings,
            onSettle = check::settle,
            onRemoveChoice = check::removeChoice,
        )
    }
}

class ReviewActions(
    val onAccept: (editId: Int) -> Unit,
    val onAcceptAll: (EditKind) -> Unit,
    val onUndo: () -> Unit,
    /** Closes the card, handing back the accepted changes. */
    val onApply: () -> Unit,
)
