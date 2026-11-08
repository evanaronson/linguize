package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.corpus.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab Chat's Send: check first; nothing to fix sends at once; fixes offer Send with fixes or Send as written. */
class SendFlowTest {
    private val draft = "Hola, ayer fuimos a la playa y fue muy bonito"
    private val fix = Edit(0, EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")
    private val rewording = Edit(1, EditKind.Natural, 31, 45, "fue muy bonito", "estuvo genial", null)
    private val revision = Revision(draft, listOf(fix, rewording))
    private val reviewed = CheckResult.Reviewed(revision, listOf(EditKind.Fix, EditKind.Natural))
    private val clean = CheckResult.Reviewed(Revision(draft, emptyList()), listOf(EditKind.Fix, EditKind.Natural))

    @Test fun sendChecksTheTrimmedDraftFirst() {
        val transition = SendState.Composing.on(SendEvent.Send("  $draft \n"))
        assertEquals(SendState.Checking(draft), transition.state)
        assertEquals(Effect.StartCheck(draft), transition.effect)
    }

    @Test fun aBlankDraftSendsNothing() {
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Send("   ")))
    }

    @Test fun nothingToFixSendsAtOnceAndIsRecorded() {
        val transition = SendState.Checking(draft).on(SendEvent.Checked(clean))
        assertEquals(SendState.Composing, transition.state)
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.LooksGood), record = true), transition.effect)
    }

    @Test fun rewordingsAloneDontHoldAMessageUp() {
        val onlyRewording = CheckResult.Reviewed(Revision(draft, listOf(rewording)), listOf(EditKind.Fix, EditKind.Natural))
        val transition = SendState.Checking(draft).on(SendEvent.Checked(onlyRewording))
        assertEquals(SendState.Composing, transition.state)
        // Withheld fixes are something to look at, though.
        val withheld = onlyRewording.copy(withheld = setOf(EditKind.Fix))
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.Checked(withheld)).state)
    }

    @Test fun fixesShowAndSendWithFixesTakesWhatWasKept() {
        val reviewing = SendState.Checking(draft).on(SendEvent.Checked(reviewed))
        assertEquals(Transition(SendState.Reviewing(draft)), reviewing)
        val sent = reviewing.state.on(SendEvent.SendWithFixes(revision.accept(fix.id)))
        assertEquals(SendState.Composing, sent.state)
        val deliver = sent.effect as Effect.Deliver
        assertEquals(true, deliver.record)
        assertEquals(draft, deliver.outgoing.raw)
        assertEquals("Hola, ayer fuimos a la plaza y fue muy bonito", deliver.outgoing.text)
        assertEquals(SentCheck.Corrected, deliver.outgoing.check)
        assertEquals(listOf(ChatChange(EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")), deliver.outgoing.changes)
    }

    @Test fun sendAsWrittenIsAlwaysThere() {
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.AsWritten), record = true),
            SendState.Reviewing(draft).on(SendEvent.SendAsWritten).effect)
        // Before the check answers, nothing was checked and nothing is saved.
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.None), record = false),
            SendState.Checking(draft).on(SendEvent.SendAsWritten).effect)
    }

    @Test fun skippingEveryFixSendsAsWritten() {
        val sent = SendState.Reviewing(draft).on(SendEvent.SendWithFixes(revision))
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.AsWritten), record = true), sent.effect)
    }

    @Test fun aFailedOrUnclearCheckStillOffersTheReview() {
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.CheckFailed).state)
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.Checked(CheckResult.Unclear)).state)
        assertEquals(SendState.Reviewing(draft),
            SendState.Checking(draft).on(SendEvent.Checked(CheckResult.WrongLanguage("Spanish", "English"))).state)
    }

    @Test fun editingClosesTheReviewAndCancelsTheCheck() {
        assertEquals(Transition(SendState.Composing, Effect.CancelCheck), SendState.Checking(draft).on(SendEvent.Edit))
        assertEquals(Transition(SendState.Composing, Effect.CancelCheck), SendState.Reviewing(draft).on(SendEvent.Edit))
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Edit))
    }

    @Test fun staleAnswersAndRevisionsAreIgnored() {
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Checked(clean)))
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Reviewing(draft).on(SendEvent.Checked(clean)))
        val other = Revision("Something else", emptyList())
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Reviewing(draft).on(SendEvent.SendWithFixes(other)))
        assertEquals(Transition(SendState.Checking(draft)), SendState.Checking(draft).on(SendEvent.Send("x")))
    }

    @Test fun aRewordingOverAnAcceptedFixKeepsBothAgainstTheRawText() {
        val accepted = revision.accept(fix.id).accept(rewording.id)
        val outgoing = Outgoings.accepted(accepted)
        assertEquals("Hola, ayer fuimos a la plaza y estuvo genial", outgoing.text)
        assertEquals(listOf(EditKind.Fix, EditKind.Natural), outgoing.changes.map { it.kind })
        val message = outgoing.message("m", "c", 1, sessionId = "s1")
        assertEquals(listOf(Span(23, 28)), message.correctedSpans)
        assertEquals("s1", message.sessionId)
        assertNull(outgoing.changes[1].why)
    }
}
