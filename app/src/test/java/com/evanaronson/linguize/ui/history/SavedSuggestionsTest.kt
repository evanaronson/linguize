package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.interpret
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
    private val assumption = Assumption("Who?", "One person", alternatives = listOf("Several people"))

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
    fun supersededReplayStaysInvalidWhileLiteralCurrentRowsRemainReadable() {
        val detail = closed()
        val old = detail.suggestions.map { it.copy(decision = Decision.Superseded) }
        val current = detail.suggestions.first().copy(id = "current", attempt = 1)
        // Superseded attempt 0 cannot replay; the comparison still retains attempt 1's literal rows.
        val damaged = detail.copy(session = detail.session.copy(attempts = detail.session.attempts + Attempt(4)),
            suggestions = old + current)
        assertNull(damaged.decidedAttempt())
        assertEquals(listOf(current), savedSuggestions(damaged))
        assertTrue(savedSuggestions(detail.copy(suggestions = old)).isEmpty())
    }

    @Test
    fun dependenciesRequireOneExactStructuralMatchAndLeaveSavedEvidenceIntact() {
        val row = closed().suggestions.first().copy(why = "Saved reason", assessment = SuggestionAssessment(1, 2))
        val linked = revision.edits.first().copy(why = "Current parser reason", assumptionIndices = listOf(0),
            assessment = SuggestionAssessment(3, 3))
        val reviewed = CheckResult.Reviewed(Revision(original, listOf(linked)), listOf(EditKind.Fix), listOf(assumption))
        assertEquals(listOf(assumption), savedSuggestionAssumptions(row, reviewed, emptyList()))
        assertEquals("Saved reason", row.why)
        assertEquals(SuggestionAssessment(1, 2), row.assessment)
        assertTrue(savedSuggestionAssumptions(row, null, emptyList()).isEmpty())
        val mismatches = listOf(
            linked.copy(kind = EditKind.Natural), linked.copy(start = 3), linked.copy(end = 8),
            linked.copy(from = "other"), linked.copy(replacement = "other"),
        )
        mismatches.forEach { mismatch ->
            assertTrue(savedSuggestionAssumptions(row, reviewed.copy(revision = Revision(original, listOf(mismatch))), emptyList()).isEmpty())
        }
        // IDs, ordering and the first matching edit cannot disambiguate duplicate proposals.
        val duplicate = linked.copy(id = 42, order = 99, assumptionIndices = emptyList())
        assertTrue(savedSuggestionAssumptions(row, reviewed.copy(revision = Revision(original, listOf(linked, duplicate))), emptyList()).isEmpty())
    }

    @Test
    fun acceptedAndRetiredRowsKeepContextFromAllEditsRatherThanRemainingEdits() {
        val fix = revision.edits.first().copy(assumptionIndices = listOf(0))
        val natural = Edit(2, EditKind.Natural, 0, 10, original, "Com et trobes?", "A rewording",
            includes = setOf(fix.id), assumptionIndices = listOf(0))
        val proposed = Revision(original, listOf(fix, natural))
        val accepted = proposed.accept(natural.id)
        assertTrue(accepted.remaining(EditKind.Fix).isEmpty())
        assertTrue(accepted.remaining(EditKind.Natural).isEmpty())
        val detail = recording().let {
            it.attempt(3, emptyList(), "saved", null, null, proposed, null, Verdict.Status.Ok)
            it.changed(accepted)
            it.close(4, accepted.workingText)
        }
        val rows = savedSuggestions(detail)
        assertEquals(listOf(Decision.Retired, Decision.Accepted), rows.map { it.decision })
        val reviewed = CheckResult.Reviewed(accepted, EditKind.entries, listOf(assumption))
        rows.forEach { assertEquals(listOf(assumption), savedSuggestionAssumptions(it, reviewed, emptyList())) }
        assertEquals(rows, savedSuggestions(detail))
    }

    @Test
    fun savedContextFiltersInvalidSettledAndAmbiguousAssumptions() {
        val row = closed().suggestions.first()
        val other = Assumption("When?", "Now")
        val edit = revision.edits.first().copy(assumptionIndices = listOf(-1, 0, 0, 1, 2, 3, 99))
        val reviewed = CheckResult.Reviewed(Revision(original, listOf(edit)), listOf(EditKind.Fix),
            listOf(assumption, other, Assumption("Where?", "Here"), Assumption("Where?", "There")))
        assertEquals(listOf(other), savedSuggestionAssumptions(row, reviewed, listOf(Settled(assumption.about, "Several people"))))
        assertTrue(savedSuggestionAssumptions(row, reviewed.copy(revision = revision), emptyList()).isEmpty())
    }

    @Test
    fun exactReplayProvidesDependenciesWhileRowFallbackDoesNotInventThem() {
        val verdict = Verdict(Verdict.Status.Ok, language = "Catalan", assumptions = listOf(assumption),
            hasErrors = true, corrected = "Com estàs?",
            fixes = listOf(VerdictChange("estas", "estàs", "Current reason", assumptionIndices = listOf(0))))
        val interpreted = interpret(original, verdict) as CheckResult.Reviewed
        val detail = recording().let {
            it.attempt(3, emptyList(), "saved", null, null, interpreted.revision, null, Verdict.Status.Ok)
            it.close(4, null)
        }
        val rows = savedSuggestions(detail)
        val exact = replay(detail) { verdict }!!.result as CheckResult.Reviewed
        assertEquals(listOf(assumption), savedSuggestionAssumptions(rows.single(), exact, emptyList()))
        val changedRows = rows.map { it.copy(toText = "ets", why = "Original saved explanation") }
        val changed = detail.copy(suggestions = detail.suggestions.filter { it.decision == Decision.Superseded } + changedRows)
        val fallback = replay(changed) { verdict }!!.result as CheckResult.Reviewed
        assertTrue(savedSuggestionAssumptions(changedRows.single(), fallback, emptyList()).isEmpty())
        assertEquals(changedRows, savedSuggestions(changed))
    }
}
