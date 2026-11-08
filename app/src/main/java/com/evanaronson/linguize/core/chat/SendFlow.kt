package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Revision

/** Turns a checked draft into the record a sent message keeps. Pure. */
object Outgoings {
    /** Sent exactly as typed; [checked] says whether a check ran first. */
    fun asWritten(draft: String, checked: Boolean) =
        Outgoing(draft, draft, emptyList(), if (checked) SentCheck.AsWritten else SentCheck.None)

    /** The check found nothing to change. */
    fun looksGood(draft: String) = Outgoing(draft, draft, emptyList(), SentCheck.LooksGood)

    /**
     * The draft with the changes accepted in [revision] (whose original is the draft). Every
     * accepted change is kept against the raw text, including a fix a rewording later replaced,
     * since the writer still got that part wrong. Nothing accepted is the same as as-written.
     */
    fun accepted(revision: Revision): Outgoing {
        val accepted = revision.acceptedEdits
        if (accepted.isEmpty()) return asWritten(revision.original, checked = true)
        val changes = accepted.sortedWith(compareBy({ it.start }, { it.end }, { it.kind.ordinal }))
            .map { ChatChange(it.kind, it.start, it.end, it.from, it.replacement, it.why?.takeIf { why -> why.isNotBlank() }) }
        val text = revision.workingText
        return Outgoing(revision.original, text, changes, if (text == revision.original) SentCheck.AsWritten else SentCheck.Corrected)
    }
}

/**
 * Lab Chat's Send button. By default a draft is checked before it is sent: a clean check
 * sends it straight away; anything else opens the review, where the writer accepts what
 * they want and sends, or sends as written. Live checking skips the step: the writer has
 * already seen the underlines. Pure: the host runs [Effect]s and reports what happened.
 */
sealed interface SendState {
    data object Composing : SendState
    data class Checking(val draft: String) : SendState

    /** The review card is open for [draft]. */
    data class Reviewing(val draft: String) : SendState
}

sealed interface SendEvent {
    /** Send tapped with [draft]; [checkFirst] is false in live mode, where [live] is its record. */
    data class Send(val draft: String, val checkFirst: Boolean, val live: Outgoing? = null) : SendEvent

    /** The check of the current draft finished. */
    data class Checked(val result: CheckResult) : SendEvent

    /** The check failed: the card shows why, and Send as written stays available. */
    data object CheckFailed : SendEvent

    /** Send with what the writer accepted in [revision]. */
    data class SendAccepted(val revision: Revision) : SendEvent
    data object SendAsWritten : SendEvent

    /** Back to the draft (Keep editing, or the writer typed). */
    data object Edit : SendEvent
}

sealed interface Effect {
    data class StartCheck(val draft: String) : Effect
    data class Deliver(val outgoing: Outgoing) : Effect
    data object CancelCheck : Effect
}

data class Transition(val state: SendState, val effect: Effect? = null)

fun SendState.on(event: SendEvent): Transition = when (event) {
    is SendEvent.Send -> {
        val draft = event.draft.trim()
        when {
            this !is SendState.Composing || draft.isEmpty() -> Transition(this)
            !event.checkFirst -> Transition(SendState.Composing, Effect.Deliver(event.live ?: Outgoings.asWritten(draft, checked = false)))
            else -> Transition(SendState.Checking(draft), Effect.StartCheck(draft))
        }
    }
    is SendEvent.Checked -> when (this) {
        is SendState.Checking -> {
            val result = event.result
            if (result is CheckResult.Reviewed && result.looksGood) {
                Transition(SendState.Composing, Effect.Deliver(Outgoings.looksGood(draft)))
            } else {
                Transition(SendState.Reviewing(draft))
            }
        }
        else -> Transition(this)
    }
    SendEvent.CheckFailed -> if (this is SendState.Checking) Transition(SendState.Reviewing(draft)) else Transition(this)
    is SendEvent.SendAccepted -> when {
        this is SendState.Reviewing && event.revision.original == draft ->
            Transition(SendState.Composing, Effect.Deliver(Outgoings.accepted(event.revision)))
        else -> Transition(this)
    }
    SendEvent.SendAsWritten -> when (this) {
        is SendState.Checking -> Transition(SendState.Composing, Effect.Deliver(Outgoings.asWritten(draft, checked = false)))
        is SendState.Reviewing -> Transition(SendState.Composing, Effect.Deliver(Outgoings.asWritten(draft, checked = true)))
        SendState.Composing -> Transition(this)
    }
    SendEvent.Edit -> when (this) {
        SendState.Composing -> Transition(this)
        else -> Transition(SendState.Composing, Effect.CancelCheck)
    }
}
