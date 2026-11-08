package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision

/** Turns a checked draft into the record a sent message keeps. Pure. */
object Outgoings {
    /** Sent exactly as typed; [checked] says whether a check had answered. */
    fun asWritten(draft: String, checked: Boolean) =
        Outgoing(draft, draft, emptyList(), if (checked) SentCheck.AsWritten else SentCheck.None)

    /** The check found nothing to fix. */
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

    /** Whether a check's answer leaves nothing to fix, so the message goes at once. Rewordings alone don't hold it up. */
    fun nothingToFix(result: CheckResult): Boolean =
        result is CheckResult.Reviewed && result.revision.edits(EditKind.Fix).isEmpty() && EditKind.Fix !in result.withheld
}

/**
 * Lab Chat's Send button: a draft is checked before it is sent. When there is nothing to fix
 * it goes at once; otherwise the fixes show over the composer, and the writer sends with the
 * fixes they kept, or as written. Pure: the host runs [Effect]s and reports what happened.
 */
sealed interface SendState {
    data object Composing : SendState
    data class Checking(val draft: String) : SendState

    /** The fixes for [draft] are on show. */
    data class Reviewing(val draft: String) : SendState
}

sealed interface SendEvent {
    /** Send tapped with [draft]. */
    data class Send(val draft: String) : SendEvent

    /** The check of the current draft finished. */
    data class Checked(val result: CheckResult) : SendEvent

    /** The check failed: the reason shows, and Send as written stays available. */
    data object CheckFailed : SendEvent

    /** Send with what the writer kept in [revision]. */
    data class SendWithFixes(val revision: Revision) : SendEvent
    data object SendAsWritten : SendEvent

    /** Back to the draft (Keep editing, or Back). */
    data object Edit : SendEvent
}

sealed interface Effect {
    data class StartCheck(val draft: String) : Effect

    /** Send [outgoing]; [record] when a check answered it, so it is saved in History. */
    data class Deliver(val outgoing: Outgoing, val record: Boolean) : Effect
    data object CancelCheck : Effect
}

data class Transition(val state: SendState, val effect: Effect? = null)

fun SendState.on(event: SendEvent): Transition = when (event) {
    is SendEvent.Send -> {
        val draft = event.draft.trim()
        if (this !is SendState.Composing || draft.isEmpty()) Transition(this)
        else Transition(SendState.Checking(draft), Effect.StartCheck(draft))
    }
    is SendEvent.Checked -> when {
        this !is SendState.Checking -> Transition(this)
        Outgoings.nothingToFix(event.result) -> Transition(SendState.Composing, Effect.Deliver(Outgoings.looksGood(draft), record = true))
        else -> Transition(SendState.Reviewing(draft))
    }
    SendEvent.CheckFailed -> if (this is SendState.Checking) Transition(SendState.Reviewing(draft)) else Transition(this)
    is SendEvent.SendWithFixes -> when {
        this is SendState.Reviewing && event.revision.original == draft ->
            Transition(SendState.Composing, Effect.Deliver(Outgoings.accepted(event.revision), record = true))
        else -> Transition(this)
    }
    SendEvent.SendAsWritten -> when (this) {
        is SendState.Checking -> Transition(SendState.Composing, Effect.Deliver(Outgoings.asWritten(draft, checked = false), record = false))
        is SendState.Reviewing -> Transition(SendState.Composing, Effect.Deliver(Outgoings.asWritten(draft, checked = true), record = true))
        SendState.Composing -> Transition(this)
    }
    SendEvent.Edit -> when (this) {
        SendState.Composing -> Transition(this)
        else -> Transition(SendState.Composing, Effect.CancelCheck)
    }
}
