package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A past session's card, rebuilt from what was recorded. */
class ReplayTest {
    private val rain = "Com estas amb la pluja?"
    private val verdict = Verdict(
        status = Verdict.Status.Ok,
        hasErrors = true,
        corrected = "Com estàs amb la pluja?",
        moreNatural = true,
        natural = "Com portes la pluja?",
        meaning = "How are you finding the rain?",
    )

    /** Answers stand for verdicts by name, so no JSON is needed. */
    private val answers = mutableMapOf("rain" to verdict)
    private val parse: (String) -> Verdict = { answers.getValue(it) }

    private val context = SessionContext(
        Opening(Origin.Menu, null, "ca", SessionSettings("English", "moderate", "both", "gemini", "m", "p1")),
        appVersion = "1.0",
        deviceId = "device",
    )

    private fun revision() = (interpret(rain, verdict) as CheckResult.Reviewed).revision

    /** A session answered with "rain", where the writer accepted the fix and sent it. */
    private fun fixApplied(): SessionDetail {
        val recording = SessionRecording(rain, context, 100)
        recording.attempt(200, listOf(Settled("tu", "informal")), "rain", null, null, revision(), null, Verdict.Status.Ok)
        val fixed = revision().acceptAll(EditKind.Fix)
        recording.changed(fixed)
        return recording.close(300, fixed.workingText)
    }

    @Test
    fun theCardShowsWhatWasTakenWithTheAnswersItWasMadeWith() {
        val replayed = replay(fixApplied(), parse)!!
        val result = replayed.result as CheckResult.Reviewed
        assertEquals("Com estàs amb la pluja?", result.revision.workingText)
        assertEquals("How are you finding the rain?", result.meaning)
        assertEquals(listOf(Settled("tu", "informal")), replayed.settled)
    }

    @Test
    fun whatWasTakenSurvivesAnEngineThatNoLongerSuggestsTheSame() {
        // Today's engine would suggest another fix: the recorded rows are what's shown.
        val rows = fixApplied().suggestions
        val today = Revision(rain, revision().edits.map { if (it.kind == EditKind.Fix) it.copy(replacement = "està") else it })
        val rebuilt = recorded(today, rows)
        assertEquals("Com estàs amb la pluja?", rebuilt.workingText)
        assertEquals(rows.size, rebuilt.edits.size)
        assertEquals(1, rebuilt.remaining(EditKind.Natural).size)
    }

    @Test
    fun identicalSuggestionsPairOffOneToOne() {
        val original = "espera"
        val dots = (interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "espera...")) as CheckResult.Reviewed).revision
        val recording = SessionRecording(original, context, 100)
        recording.attempt(200, emptyList(), "dots", null, null, dots, null, Verdict.Status.Ok)
        val one = dots.accept(dots.edits.first().id)
        recording.changed(one)
        val detail = recording.close(300, one.workingText)
        assertEquals(one.workingText, recorded(dots, detail.suggestions).workingText)
    }

    @Test
    fun aRewordingThatWasTakenHidesTheFixesItIncludes() {
        val recording = SessionRecording(rain, context, 100)
        recording.attempt(200, emptyList(), "rain", null, null, revision(), null, Verdict.Status.Ok)
        val reworded = revision().acceptAll(EditKind.Natural)
        recording.changed(reworded)
        val rows = recording.close(300, reworded.workingText).suggestions
        // Built from the rows alone, as when the engine has changed.
        val rebuilt = recorded(Revision(rain, emptyList()), rows)
        assertEquals("Com portes la pluja?", rebuilt.workingText)
        assertTrue(rebuilt.remaining(EditKind.Fix).isEmpty())
    }

    @Test
    fun theDecidedAttemptIsShownNotTheLastOne() {
        answers["unclear"] = Verdict(status = Verdict.Status.Unclear)
        val recording = SessionRecording(rain, context, 100)
        recording.attempt(200, emptyList(), "rain", null, null, revision(), null, Verdict.Status.Ok)
        recording.attempt(250, listOf(Settled("who", "me")), "unclear", null, null, null, null, Verdict.Status.Unclear)
        val replayed = replay(recording.close(300, null), parse)!!
        assertTrue(replayed.result is CheckResult.Reviewed)
        assertEquals(emptyList<Settled>(), replayed.settled)
    }

    @Test
    fun nothingWhenNoAttemptSucceededOrTheAnswerCantBeRead() {
        val failed = SessionRecording(rain, context, 100)
        failed.attempt(200, emptyList(), "garbled", "BadResponse", null, null, null)
        assertNull(replay(failed.close(300, null), parse))

        val unreadable = SessionRecording(rain, context, 100)
        unreadable.attempt(200, emptyList(), "not an answer we know", null, null, null, null, Verdict.Status.Ok)
        assertNull(replay(unreadable.close(300, null), parse))
    }
}
