package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.corpus.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab Chat's Send: check, review, send; send as written; already fine. */
class SendFlowTest {
    private val draft = "Hola, ayer fuimos a la playa y fue muy bonito"
    private val fix = Edit(0, EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")
    private val rewording = Edit(1, EditKind.Natural, 31, 45, "fue muy bonito", "estuvo genial", null)
    private val revision = Revision(draft, listOf(fix, rewording))
    private val reviewed = CheckResult.Reviewed(revision, listOf(EditKind.Fix, EditKind.Natural))
    private val clean = CheckResult.Reviewed(Revision(draft, emptyList()), listOf(EditKind.Fix, EditKind.Natural))

    @Test fun sendChecksTheTrimmedDraftFirst() {
        val transition = SendState.Composing.on(SendEvent.Send("  $draft \n", checkFirst = true))
        assertEquals(SendState.Checking(draft), transition.state)
        assertEquals(Effect.StartCheck(draft), transition.effect)
    }

    @Test fun aBlankDraftSendsNothing() {
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Send("   ", checkFirst = true)))
    }

    @Test fun aCleanCheckSendsStraightAwayMarkedLooksGood() {
        val transition = SendState.Checking(draft).on(SendEvent.Checked(clean))
        assertEquals(SendState.Composing, transition.state)
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.LooksGood)), transition.effect)
    }

    @Test fun changesOpenTheReviewAndSendTakesWhatWasAccepted() {
        val reviewing = SendState.Checking(draft).on(SendEvent.Checked(reviewed))
        assertEquals(Transition(SendState.Reviewing(draft)), reviewing)
        val sent = reviewing.state.on(SendEvent.SendAccepted(revision.accept(fix.id)))
        assertEquals(SendState.Composing, sent.state)
        val outgoing = (sent.effect as Effect.Deliver).outgoing
        assertEquals(draft, outgoing.raw)
        assertEquals("Hola, ayer fuimos a la plaza y fue muy bonito", outgoing.text)
        assertEquals(SentCheck.Corrected, outgoing.check)
        assertEquals(listOf(ChatChange(EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")), outgoing.changes)
    }

    @Test fun sendAsWrittenIsAlwaysThere() {
        val reviewing = SendState.Reviewing(draft)
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.AsWritten)), reviewing.on(SendEvent.SendAsWritten).effect)
        // Before the check even answers: nothing was checked.
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.None)),
            SendState.Checking(draft).on(SendEvent.SendAsWritten).effect)
    }

    @Test fun aFailedOrUnclearCheckStillOffersTheReview() {
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.CheckFailed).state)
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.Checked(CheckResult.Unclear)).state)
        assertEquals(SendState.Reviewing(draft),
            SendState.Checking(draft).on(SendEvent.Checked(CheckResult.WrongLanguage("Spanish", "English"))).state)
    }

    @Test fun nothingAcceptedSendsAsWritten() {
        val sent = SendState.Reviewing(draft).on(SendEvent.SendAccepted(revision))
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.AsWritten)), sent.effect)
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
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Reviewing(draft).on(SendEvent.SendAccepted(other)))
        // A second Send while one is checking does nothing.
        assertEquals(Transition(SendState.Checking(draft)), SendState.Checking(draft).on(SendEvent.Send("x", checkFirst = true)))
    }

    @Test fun liveModeSendsItsOwnRecordWithoutAReview() {
        val live = Outgoing("Hola amigo", "Hola, amigo", listOf(ChatChange(EditKind.Fix, 4, 4, "", ",")), SentCheck.Corrected)
        val transition = SendState.Composing.on(SendEvent.Send("Hola, amigo", checkFirst = false, live = live))
        assertEquals(Transition(SendState.Composing, Effect.Deliver(live)), transition)
    }

    @Test fun aRewordingOverAnAcceptedFixKeepsBothAgainstTheRawText() {
        val accepted = revision.accept(fix.id).accept(rewording.id)
        val outgoing = Outgoings.accepted(accepted)
        assertEquals("Hola, ayer fuimos a la plaza y estuvo genial", outgoing.text)
        assertEquals(listOf(EditKind.Fix, EditKind.Natural), outgoing.changes.map { it.kind })
        val message = outgoing.message("m", "c", 1)
        // Only fixes mark the writer's words as tried and wrong.
        assertEquals(listOf(Span(23, 28)), message.correctedSpans)
        assertNull(outgoing.changes[1].why)
    }
}
