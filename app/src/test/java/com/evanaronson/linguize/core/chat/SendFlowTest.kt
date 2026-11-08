package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.corpus.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lab Chat's Send: the same check as a message in another app, forced. Nothing to review sends
 * at once; otherwise the card's Send sends what was accepted; a failure can't be bypassed.
 */
class SendFlowTest {
    private val draft = "Hola, ayer fuimos a la playa y fue muy bonito"
    private val fix = Edit(0, EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")
    private val other = Edit(1, EditKind.Fix, 11, 17, "fuimos", "fuimos", null)
    private val rewording = Edit(2, EditKind.Natural, 31, 45, "fue muy bonito", "estuvo genial", null)
    private val revision = Revision(draft, listOf(fix, rewording))
    private val reviewed = CheckResult.Reviewed(revision, listOf(EditKind.Fix, EditKind.Natural))
    private val clean = CheckResult.Reviewed(Revision(draft, emptyList()), listOf(EditKind.Fix, EditKind.Natural))

    @Test fun sendChecksTheTrimmedDraftFirst() {
        val transition = SendState.Composing.on(SendEvent.Send("  $draft \n"))
        assertEquals(SendState.Checking(draft), transition.state)
        assertEquals(Effect.StartCheck(draft), transition.effect)
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Send("   ")))
    }

    @Test fun nothingToReviewSendsAtOnceMarkedLooksGood() {
        val transition = SendState.Checking(draft).on(SendEvent.Checked(clean))
        assertEquals(Transition(SendState.Composing, Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.LooksGood))), transition)
    }

    @Test fun anyChangeOpensTheCardEvenARewordingAlone() {
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Checking(draft).on(SendEvent.Checked(reviewed)))
        val onlyRewording = CheckResult.Reviewed(Revision(draft, listOf(rewording)), listOf(EditKind.Fix, EditKind.Natural))
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.Checked(onlyRewording)).state)
    }

    @Test fun theCardsSendSendsTheAcceptedSubset() {
        val both = Revision(draft, listOf(other, fix, rewording))
        val sent = SendState.Reviewing(draft).on(SendEvent.SendReviewed(both.accept(fix.id)))
        assertEquals(SendState.Composing, sent.state)
        val outgoing = (sent.effect as Effect.Deliver).outgoing
        assertEquals(draft, outgoing.raw)
        assertEquals("Hola, ayer fuimos a la plaza y fue muy bonito", outgoing.text)
        assertEquals(SentCheck.Corrected, outgoing.check)
        assertEquals(listOf(ChatChange(EditKind.Fix, 23, 28, "playa", "plaza", "Wrong word")), outgoing.changes)
    }

    @Test fun acceptingIsntForcedSendWithNothingAcceptedSendsTheTextAsTyped() {
        val sent = SendState.Reviewing(draft).on(SendEvent.SendReviewed(revision))
        assertEquals(Effect.Deliver(Outgoing(draft, draft, emptyList(), SentCheck.AsWritten)), sent.effect)
    }

    @Test fun aFailureOrUnclearCheckShowsTheCardWithNoWayToSend() {
        val failed = SendState.Checking(draft).on(SendEvent.CheckFailed)
        assertEquals(Transition(SendState.Reviewing(draft)), failed)
        assertEquals(SendState.Reviewing(draft), SendState.Checking(draft).on(SendEvent.Checked(CheckResult.Unclear)).state)
        assertEquals(SendState.Reviewing(draft),
            SendState.Checking(draft).on(SendEvent.Checked(CheckResult.WrongLanguage("Spanish", "English"))).state)
        // Only Keep editing (or Retry on the card) leaves it; nothing delivers without a reviewed answer.
        val events = listOf(SendEvent.CheckFailed, SendEvent.Checked(CheckResult.Unclear), SendEvent.Send(draft))
        events.forEach { assertNull(SendState.Reviewing(draft).on(it).effect) }
        assertEquals(Transition(SendState.Composing, Effect.CancelCheck), SendState.Reviewing(draft).on(SendEvent.Edit))
    }

    @Test fun aRetryThatComesBackCleanLeavesTheCardOpenForSend() {
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Reviewing(draft).on(SendEvent.Checked(clean)))
    }

    @Test fun staleAnswersAndRevisionsAreIgnored() {
        assertEquals(Transition(SendState.Composing), SendState.Composing.on(SendEvent.Checked(clean)))
        val other = Revision("Something else", emptyList())
        assertEquals(Transition(SendState.Reviewing(draft)), SendState.Reviewing(draft).on(SendEvent.SendReviewed(other)))
        assertEquals(Transition(SendState.Checking(draft)), SendState.Checking(draft).on(SendEvent.SendReviewed(revision)))
        assertEquals(Transition(SendState.Composing, Effect.CancelCheck), SendState.Checking(draft).on(SendEvent.Edit))
    }

    @Test fun aRewordingOverAnAcceptedFixKeepsBothAgainstTheRawText() {
        val accepted = revision.accept(fix.id).accept(rewording.id)
        val outgoing = Outgoings.accepted(accepted)
        assertEquals("Hola, ayer fuimos a la plaza y estuvo genial", outgoing.text)
        assertEquals(listOf(EditKind.Fix, EditKind.Natural), outgoing.changes.map { it.kind })
        val message = outgoing.message("m", "c", 1, sessionId = "s1")
        assertEquals(listOf(Span(23, 28)), message.correctedSpans)
        assertEquals("s1", message.sessionId)
    }
}
