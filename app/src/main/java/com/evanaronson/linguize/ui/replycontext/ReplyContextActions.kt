package com.evanaronson.linguize.ui.replycontext

import com.evanaronson.linguize.ui.card.CheckViewModel

/** Lab: what the card needs for "Replying to": the attached message, and attaching or removing it. */
class ReplyContextActions(
    private val current: () -> String?,
    /** Returns false when the message can't be attached (blank or too long). */
    val attach: (String) -> Boolean,
    val remove: () -> Unit,
) {
    /** The attached message, read from state so the card recomposes when it changes. */
    val message: String? get() = current()

    companion object {
        fun of(check: CheckViewModel) = ReplyContextActions(
            current = { check.replyingTo },
            attach = { check.replyTo(it) },
            remove = { check.replyTo(null) },
        )
    }
}
