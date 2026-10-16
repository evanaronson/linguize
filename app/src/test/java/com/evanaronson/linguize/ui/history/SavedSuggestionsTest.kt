package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.history.replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSuggestionsTest {
    private val original = "Com estas?"
    private val revision = Revision(original, listOf(
        Edit(0, EditKind.Fix, 4, 9, "estas", "estàs", why = "Accent marks the verb."),
        Edit(1, EditKind.Fix, 9, 10, "?", "!", why = "An exclamation."),
    ))

    private fun recording(): SessionRecording {
        val context = SessionContext(Opening(Origin.Menu, null, "ca",
            SessionSettings("English", "moderate", "both", "gemini", "m", "p")), "1", "device")
        return SessionRecording(original, context, 1).also {
            it.attempt(2, emptyList(), "saved", null, null, revision, null, Verdict.Status.Ok)
        }
    }

    private fun closed(accepted: Revision = revision): SessionDetail = recording().let {
        it.changed(accepted)
        it.close(3, accepted.workingText)
    }

    @Test
    fun allAppliedRetainsEveryReasonForBothAcceptancePaths() {
        val all = revision.acceptAll(EditKind.Fix)
        val individual = revision.accept(0).accept(1)
        listOf(all, individual).forEach { accepted ->
            val detail = closed(accepted)
            assertEquals(original, detail.session.text)
            assertEquals("Com estàs!", detail.session.finalText)
            val rows = savedSuggestions(detail)
            assertEquals(revision.edits.map { it.why }, rows.map { it.why })
            assertEquals(listOf(R.string.history_applied, R.string.history_applied),
                rows.map { savedSuggestionState(it.decision, detail.session.finalText != null) })
        }
    }

    @Test
    fun mixedResultsKeepUnappliedProposalsAlongsideAppliedOnes() {
        val detail = closed(revision.accept(0))
        assertEquals("Com estàs?", detail.session.finalText)
        assertEquals(listOf(R.string.history_applied, R.string.history_not_applied),
            savedSuggestions(detail).map { savedSuggestionState(it.decision, true) })
    }

    @Test
    fun copyAndFailedHandoffNeverInventAnAppliedResult() {
        val copied = recording().let { it.copied(EditKind.Fix); it.close(3, null) }
        assertNull(copied.session.finalText)
        assertEquals(Outcome.Copied, copied.session.outcome)
        assertTrue(savedSuggestions(copied).all { savedSuggestionState(it.decision, false) == R.string.history_copied })
        val failedHandoff = recording().let { it.changed(revision.acceptAll(EditKind.Fix)); it.close(3, null) }
        assertNull(failedHandoff.session.finalText)
        assertTrue(savedSuggestions(failedHandoff).all { savedSuggestionState(it.decision, false) == R.string.history_not_applied })
        assertEquals(R.string.history_application_not_recorded, savedSuggestionState(Decision.Accepted, false))
        // Retired never implies that this fix was applied independently or identifies a parent.
        assertEquals(R.string.history_covered, savedSuggestionState(Decision.Retired, false))
        assertEquals(R.string.history_covered, savedSuggestionState(Decision.Retired, true))
    }

    @Test
    fun laterFailedOrUnclearRequestDoesNotHideTheDecidedRevision() {
        listOf(Attempt(4, failure = "network"), Attempt(4, raw = "unclear")).forEach { later ->
            val detail = closed(revision.accept(0))
            val withRetry = detail.copy(session = detail.session.copy(attempts = detail.session.attempts + later))
            assertEquals(detail.suggestions, savedSuggestions(withRetry))
        }
    }

    @Test
    fun missingRawAndInvalidSpansPreserveLiteralRowsAndTheirSeparateIdentities() {
        val detail = closed()
        val first = detail.suggestions.first().copy(start = -20, end = 999, fromText = "", toText = ".", why = null)
        val rows = listOf(first, first.copy(id = "second", why = "Another insertion"))
        val damaged = detail.copy(session = detail.session.copy(attempts = listOf(Attempt(2))), suggestions = rows)
        assertNull(damaged.decidedAttempt())
        assertNull(replay(damaged) { error("No raw answer to read") })
        assertEquals(rows, savedSuggestions(damaged))
        assertEquals(2, savedSuggestions(damaged).map { it.id }.distinct().size)
        val unreadable = damaged.copy(session = detail.session)
        assertNull(replay(unreadable) { error("Invalid spans must not reach parsing") })
        assertEquals(rows, savedSuggestions(unreadable))
    }

    @Test
    fun onlyFinalNonSupersededRevisionIsShownWithoutChangingSharedSelection() {
        val detail = closed()
        val old = detail.suggestions.map { it.copy(decision = Decision.Superseded) }
        val current = detail.suggestions.first().copy(id = "current", attempt = 1)
        // Shared replay can only read attempt 0; the comparison still retains attempt 1's rows.
        val damaged = detail.copy(session = detail.session.copy(attempts = detail.session.attempts + Attempt(4)),
            suggestions = old + current)
        assertEquals(0, damaged.decidedAttempt())
        assertEquals(listOf(current), savedSuggestions(damaged))
        assertTrue(savedSuggestions(detail.copy(suggestions = old)).isEmpty())
    }
}
