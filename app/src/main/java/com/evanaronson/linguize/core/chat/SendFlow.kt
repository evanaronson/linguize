package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Revision

/** Turns a checked draft into the record a sent message keeps. Pure. */
object Outgoings {
    /** The check found nothing to change. */
    fun looksGood(draft: String) = Outgoing(draft, draft, emptyList(), SentCheck.LooksGood)

    /**
     * The draft with the changes accepted in [revision] (whose original is the draft), exactly
     * as Apply hands text back to another app. Every accepted change is kept against the raw
     * text, including a fix a rewording later replaced, since the writer still got that part
     * wrong. Nothing accepted sends the text as typed, marked as reviewed.
     */
    fun accepted(revision: Revision): Outgoing {
        val accepted = revision.acceptedEdits
        if (accepted.isEmpty()) return Outgoing(revision.original, revision.original, emptyList(), SentCheck.AsWritten)
        val changes = accepted.sortedWith(compareBy({ it.start }, { it.end }, { it.kind.ordinal }))
            .map { ChatChange(it.kind, it.start, it.end, it.from, it.replacement, it.why?.takeIf { why -> why.isNotBlank() }) }
        val text = revision.workingText
        return Outgoing(revision.original, text, changes, if (text == revision.original) SentCheck.AsWritten else SentCheck.Corrected)
    }

    /** Whether a check's answer has nothing to review, so the message goes at once. */
    fun nothingToReview(result: CheckResult): Boolean = result is CheckResult.Reviewed && result.looksGood
}

/**
 * Lab Chat's Send button: the same check as a message in another app, forced, because the
 * chat has no selection menu. When the check has nothing to review the message goes at once;
 * otherwise the full card opens, and its Send (Apply) sends the text with whatever the writer
 * accepted. A failed, unclear or wrong-language check can be retried or edited, never skipped.
 * Pure: the host runs [Effect]s and reports what happened.
 */
sealed interface SendState {
    data object Composing : SendState
    data class Checking(val draft: String) : SendState

    /** The card is open for [draft]. */
    data class Reviewing(val draft: String) : SendState
}

sealed interface SendEvent {
    /** Send tapped with [draft]. */
    data class Send(val draft: String) : SendEvent

    /** The check of the current draft answered. */
    data class Checked(val result: CheckResult) : SendEvent

    /** The check failed: the card shows why, with Retry. */
    data object CheckFailed : SendEvent

    /** The card's Send: [revision] holds what the writer accepted (possibly nothing). */
    data class SendReviewed(val revision: Revision) : SendEvent

    /** Back to the draft (Keep editing, or Back). */
    data object Edit : SendEvent
}

sealed interface Effect {
    data class StartCheck(val draft: String) : Effect

    /** Send [outgoing]; it was checked, so it is saved in History. */
    data class Deliver(val outgoing: Outgoing) : Effect
    data object CancelCheck : Effect
}

data class Transition(val state: SendState, val effect: Effect? = null)

fun SendState.on(event: SendEvent): Transition = when (event) {
    is SendEvent.Send -> {
        val draft = event.draft.trim()
        if (this !is SendState.Composing || draft.isEmpty()) Transition(this)
        else Transition(SendState.Checking(draft), Effect.StartCheck(draft))
    }
    is SendEvent.Checked -> when (this) {
        is SendState.Checking ->
            if (Outgoings.nothingToReview(event.result)) Transition(SendState.Composing, Effect.Deliver(Outgoings.looksGood(draft)))
            else Transition(SendState.Reviewing(draft))
        // A retry or clarification on the open card answers again: the card shows it.
        else -> Transition(this)
    }
    SendEvent.CheckFailed -> if (this is SendState.Checking) Transition(SendState.Reviewing(draft)) else Transition(this)
    is SendEvent.SendReviewed -> when {
        this is SendState.Reviewing && event.revision.original == draft ->
            Transition(SendState.Composing, Effect.Deliver(Outgoings.accepted(event.revision)))
        else -> Transition(this)
    }
    SendEvent.Edit -> when (this) {
        SendState.Composing -> Transition(this)
        else -> Transition(SendState.Composing, Effect.CancelCheck)
    }
}
