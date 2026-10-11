package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What becomes of each suggestion and of the session, for each way a card can be used. */
class SessionRecordingTest {
    private val context = SessionContext(
        Opening(
            origin = Origin.Menu,
            hostApp = "org.telegram.messenger",
            requestedLanguage = "ca",
            settings = SessionSettings(
                nativeLanguage = "English",
                punctuation = "Relaxed",
                judgments = "Both",
                provider = "anthropic",
                model = "a-model",
                promptHash = "p1",
            ),
        ),
        appVersion = "1.0",
        deviceId = "device",
    )

    /** A fix (estas → estàs) and a rewording that replaces it. */
    private val rain = "Com estas amb la pluja?"

    /** A fix (despues → después) and a rewording elsewhere that doesn't touch it. */
    private val shower = "Voy a tomar una ducha y te llamo despues"

    private fun revision(original: String, corrected: String = "", natural: String = ""): Revision =
        (
            interpret(
                original,
                Verdict(
                    status = Verdict.Status.Ok,
                    hasErrors = corrected.isNotEmpty(),
                    corrected = corrected,
                    moreNatural = natural.isNotEmpty(),
                    natural = natural,
                ),
            ) as CheckResult.Reviewed
            ).revision

    private fun rainRevision() = revision(rain, corrected = "Com estàs amb la pluja?", natural = "Com portes la pluja?")

    private fun showerRevision() =
        revision(shower, corrected = "Voy a tomar una ducha y te llamo después", natural = "Me voy a duchar y te llamo después")

    private var ids = 0
    private fun recording(text: String) = SessionRecording(text, context, now = 100, newId = { "id${ids++}" })

    private fun SessionRecording.succeeded(at: Long, revision: Revision?, meaning: String? = "meaning $at") =
        attempt(at, emptyList(), "{}", null, null, revision, meaning)

    private fun SessionRecording.failed(at: Long) = attempt(at, emptyList(), null, "Network", "timeout", null, null)

    private fun SessionDetail.decisions(kind: EditKind) =
        suggestions.filter { it.kind == kind }.map { it.decision }.toSet()

    private fun SessionDetail.only(kind: EditKind) = suggestions.single { it.kind == kind }

    @Test
    fun theSessionStartsOpenWithItsContext() {
        val session = recording(rain).session
        assertEquals("id0", session.id)
        assertEquals(100, session.startedAt)
        assertEquals(100, session.createdAt)
        assertEquals(100, session.updatedAt)
        assertNull(session.closedAt)
        assertNull(session.outcome)
        assertEquals(Origin.Menu, session.origin)
        assertEquals("ca", session.requestedLanguage)
        assertEquals(SessionRecording.hash(rain), session.textHash)
        assertTrue(session.attempts.isEmpty())
    }

    @Test
    fun theHashIsShortAndStable() {
        val hash = SessionRecording.hash("hola")
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertEquals(hash, SessionRecording.hash("hola"))
        assertTrue(hash != SessionRecording.hash("Hola"))
    }

    @Test
    fun nothingDoneIgnoresEverySuggestion() {
        val recording = recording(rain)
        val revision = rainRevision()
        val session = recording.succeeded(200, revision)
        assertEquals(200, session.updatedAt)
        assertEquals("meaning 200", session.meaning)

        val detail = recording.close(300, finalText = null)
        assertEquals(Outcome.None, detail.session.outcome)
        assertEquals(300L, detail.session.closedAt)
        assertEquals(300, detail.session.updatedAt)
        assertEquals(revision.edits.size, detail.suggestions.size)
        assertTrue(detail.suggestions.all { it.decision == Decision.Ignored && it.decidedAt == 300L && it.attempt == 0 })
        assertTrue(detail.suggestions.all { it.sessionId == detail.session.id && it.deviceId == "device" })
    }

    @Test
    fun aSuggestionIsRecordedAsTheCardShowedIt() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fix = recording.close(300, null).only(EditKind.Fix)
        val edit = revision.edits(EditKind.Fix).single()
        assertEquals(EditKind.Fix, fix.kind)
        assertEquals("estas", fix.fromText)
        assertEquals("estàs", fix.toText)
        assertEquals(edit.start, fix.start)
        assertEquals(edit.end, fix.end)
        assertEquals("estas", rain.substring(fix.start, fix.end))
    }

    @Test
    fun anAcceptedFixIsAccepted() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val accepted = revision.acceptAll(EditKind.Fix)
        recording.changed(accepted)

        val detail = recording.close(300, accepted.workingText)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertEquals("Com estàs amb la pluja?", detail.session.finalText)
        assertEquals(Decision.Accepted, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Ignored, detail.only(EditKind.Natural).decision)
    }

    @Test
    fun acceptedThenUndoneIsUndone() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val accepted = revision.acceptAll(EditKind.Fix)
        recording.changed(accepted)
        recording.changed(accepted.undo())

        val detail = recording.close(300, null)
        assertEquals(Outcome.None, detail.session.outcome)
        assertEquals(Decision.Undone, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Ignored, detail.only(EditKind.Natural).decision)
    }

    @Test
    fun aRewordingAcceptedOverAnAcceptedFixRetiresIt() {
        val recording = recording(rain)
        val fixed = rainRevision().acceptAll(EditKind.Fix)
        recording.succeeded(200, rainRevision())
        recording.changed(fixed)
        val both = fixed.acceptAll(EditKind.Natural)
        recording.changed(both)
        assertEquals(1, both.acceptedCount)

        val detail = recording.close(300, both.workingText)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertEquals(Decision.Retired, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Accepted, detail.only(EditKind.Natural).decision)
        assertEquals(1, detail.suggestions.count { it.decision == Decision.Accepted })
    }

    @Test
    fun aRewordingAcceptedAloneRetiresTheFixItReplaces() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val reworded = revision.acceptAll(EditKind.Natural)
        recording.changed(reworded)

        val detail = recording.close(300, reworded.workingText)
        assertEquals(Decision.Retired, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Accepted, detail.only(EditKind.Natural).decision)
    }

    @Test
    fun undoingTheRewordingBringsTheFixBackAsAccepted() {
        val recording = recording(rain)
        val fixed = rainRevision().acceptAll(EditKind.Fix)
        recording.succeeded(200, rainRevision())
        recording.changed(fixed)
        recording.changed(fixed.acceptAll(EditKind.Natural))
        recording.changed(fixed)

        val detail = recording.close(300, fixed.workingText)
        assertEquals(Decision.Accepted, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Undone, detail.only(EditKind.Natural).decision)
    }

    @Test
    fun copyingOneKindCopiesItsRemainingSuggestionsAndLeavesTheOtherIgnored() {
        val recording = recording(shower)
        val revision = showerRevision()
        assertTrue(revision.edits(EditKind.Fix).isNotEmpty())
        assertTrue(revision.edits(EditKind.Natural).isNotEmpty())
        recording.succeeded(200, revision)
        recording.copied(EditKind.Natural)

        val detail = recording.close(300, null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertNull(detail.session.finalText)
        assertEquals(setOf(Decision.Copied), detail.decisions(EditKind.Natural))
        assertEquals(setOf(Decision.Ignored), detail.decisions(EditKind.Fix))
    }

    @Test
    fun aCopiedSectionCarriesWhatWasAcceptedBeforeTheCopy() {
        val recording = recording(rain)
        val reworded = rainRevision().acceptAll(EditKind.Natural)
        recording.succeeded(200, rainRevision())
        recording.changed(reworded)
        recording.copied(EditKind.Fix)

        // Nothing went back to the app; the accepted rewording left in the copy, with the fix inside it.
        val detail = recording.close(300, null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertEquals(Decision.Copied, detail.only(EditKind.Natural).decision)
        assertEquals(Decision.Retired, detail.only(EditKind.Fix).decision)
    }

    @Test
    fun aFixTheCopiedRewordingReplacedIsRetiredNotCopied() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        // The rewording preview shows "Com portes la pluja?": the accepted fix isn't in it.
        assertTrue("estàs" !in fixed.preview(EditKind.Natural).text)
        recording.copied(EditKind.Natural)

        val detail = recording.close(300, null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertEquals(Decision.Copied, detail.only(EditKind.Natural).decision)
        assertEquals(Decision.Retired, detail.only(EditKind.Fix).decision)
    }

    @Test
    fun copyingTheSameTextEitherWayDecidesTheFixTheSame() {
        // Fix accepted, rewording on offer, Natural copied; and both accepted, Fix copied: one text, one decision.
        val viaNatural = recording(rain).apply {
            succeeded(200, rainRevision())
            changed(rainRevision().acceptAll(EditKind.Fix))
            copied(EditKind.Natural)
        }.close(300, null)
        val viaFix = recording(rain).apply {
            succeeded(200, rainRevision())
            changed(rainRevision().acceptAll(EditKind.Fix).acceptAll(EditKind.Natural))
            copied(EditKind.Fix)
        }.close(300, null)
        assertEquals(viaFix.only(EditKind.Fix).decision, viaNatural.only(EditKind.Fix).decision)
        assertEquals(viaFix.only(EditKind.Natural).decision, viaNatural.only(EditKind.Natural).decision)
    }

    @Test
    fun aChangeToAnEarlierAttemptsRevisionIsIgnored() {
        val recording = recording(shower)
        val first = showerRevision()
        recording.succeeded(200, first)
        // The re-check found only the fix.
        val second = revision(shower, corrected = "Voy a tomar una ducha y te llamo después")
        recording.succeeded(400, second)
        // A late report from the first card.
        recording.changed(first.acceptAll(EditKind.Fix))

        val detail = recording.close(500, null)
        assertTrue(detail.suggestions.filter { it.attempt == 0 }.all { it.decision == Decision.Superseded })
        assertEquals(first.edits.size, detail.suggestions.count { it.attempt == 0 })
        assertEquals(second.edits.size, detail.suggestions.count { it.attempt == 1 })
        assertTrue(detail.suggestions.filter { it.attempt == 1 }.all { it.decision == Decision.Ignored })
    }

    @Test
    fun theRawAnswerIsKeptEvenWhenItCouldntBeRead() {
        val recording = recording(rain)
        val session = recording.attempt(200, emptyList(), "not json", "BadResponse", "not in the expected format", null, null)
        assertEquals("not json", session.attempts.single().raw)
        assertTrue(!session.attempts.single().succeeded)
        assertEquals("English", session.nativeLanguage)
    }

    @Test
    fun acceptedButNeverAppliedIsUndone() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val reworded = revision.acceptAll(EditKind.Natural)
        recording.changed(reworded)

        val detail = recording.close(300, finalText = null)
        assertEquals(Outcome.None, detail.session.outcome)
        assertEquals(Decision.Undone, detail.only(EditKind.Natural).decision)
        assertEquals(Decision.Ignored, detail.only(EditKind.Fix).decision)
        assertTrue(detail.suggestions.none { it.decision == Decision.Accepted || it.decision == Decision.Retired })
    }

    @Test
    fun acceptedThenCopiedWithoutApplyingIsCopied() {
        val recording = recording(shower)
        val revision = showerRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        recording.copied(EditKind.Fix)

        val detail = recording.close(300, finalText = null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertEquals(setOf(Decision.Copied), detail.decisions(EditKind.Fix))
        assertEquals(setOf(Decision.Ignored), detail.decisions(EditKind.Natural))
    }

    @Test
    fun appliedWinsOverCopied() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        recording.copied(EditKind.Fix)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)

        val detail = recording.close(300, fixed.workingText)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertEquals(Decision.Accepted, detail.only(EditKind.Fix).decision)
    }

    @Test
    fun aRecheckSupersedesTheFirstAttemptAndDecidesTheSecond() {
        val recording = recording(rain)
        val first = rainRevision()
        recording.succeeded(200, first)
        val fixed = first.acceptAll(EditKind.Fix)
        recording.changed(fixed)

        val second = rainRevision().acceptMatching(fixed.acceptedEdits)
        val session = recording.attempt(400, listOf(Settled("who", "me")), "{}", null, null, second, "second meaning")
        assertEquals(2, session.attempts.size)
        assertEquals(listOf(Settled("who", "me")), session.attempts[1].settled)
        assertEquals(400, session.updatedAt)
        assertEquals("second meaning", session.meaning)

        val detail = recording.close(500, second.workingText)
        val (old, new) = detail.suggestions.partition { it.attempt == 0 }
        assertEquals(first.edits.size, old.size)
        assertTrue(old.all { it.decision == Decision.Superseded && it.decidedAt == 400L })
        assertEquals(second.edits.size, new.size)
        assertTrue(new.all { it.attempt == 1 && it.decidedAt == 500L })
        assertEquals(Decision.Accepted, new.single { it.kind == EditKind.Fix }.decision)
        assertEquals(Decision.Ignored, new.single { it.kind == EditKind.Natural }.decision)
        assertEquals(Outcome.Applied, detail.session.outcome)
    }

    @Test
    fun aCarriedOverAcceptUndoneAfterARecheckIsUndone() {
        val recording = recording(rain)
        val fixed = rainRevision().acceptAll(EditKind.Fix)
        recording.succeeded(200, rainRevision())
        recording.changed(fixed)

        val second = rainRevision().acceptMatching(fixed.acceptedEdits)
        recording.succeeded(400, second)
        recording.changed(second.undo())

        val detail = recording.close(500, null)
        assertEquals(Decision.Undone, detail.suggestions.single { it.attempt == 1 && it.kind == EditKind.Fix }.decision)
    }

    @Test
    fun acceptsAndCopiesOfAnEarlierAttemptDontCarryOver() {
        val recording = recording(rain)
        val first = rainRevision()
        recording.succeeded(200, first)
        recording.changed(first.acceptAll(EditKind.Fix))
        recording.changed(first)
        recording.copied(EditKind.Natural)
        recording.succeeded(400, rainRevision())

        val detail = recording.close(500, null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertTrue(detail.suggestions.filter { it.attempt == 1 }.all { it.decision == Decision.Ignored })
    }

    @Test
    fun aFailedFirstAttemptThenASuccess() {
        val recording = recording(rain)
        val failed = recording.failed(200)
        assertEquals("Network", failed.attempts.single().failure)
        assertNull(failed.meaning)

        val revision = rainRevision()
        recording.succeeded(400, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)

        val detail = recording.close(500, fixed.workingText)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertEquals("meaning 400", detail.session.meaning)
        assertEquals(2, detail.session.attempts.size)
        assertTrue(detail.suggestions.all { it.attempt == 1 })
        assertEquals(Decision.Accepted, detail.only(EditKind.Fix).decision)
    }

    @Test
    fun aFailedLastAttemptFailsTheSessionAndLeavesTheOneBeforeDecided() {
        val recording = recording(rain)
        recording.succeeded(200, rainRevision())
        recording.failed(400)

        val detail = recording.close(500, null)
        assertEquals(Outcome.Failed, detail.session.outcome)
        assertEquals("meaning 200", detail.session.meaning)
        assertEquals(2, detail.session.attempts.size)
        assertTrue(detail.suggestions.all { it.decision == Decision.Ignored && it.decidedAt == 500L && it.attempt == 0 })
    }

    @Test
    fun editsAcceptedBeforeAFailedRecheckAreDecidedWithTheirAttempt() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        recording.failed(400)

        // The card still carries the accepted fix, and Replace applies it.
        val detail = recording.close(500, fixed.workingText)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertTrue(detail.suggestions.all { it.attempt == 0 })
        assertEquals(Decision.Accepted, detail.only(EditKind.Fix).decision)
        assertEquals(Decision.Ignored, detail.only(EditKind.Natural).decision)
    }

    @Test
    fun aRecheckThatSucceedsAfterAFailedOneSupersedesTheDecidedRevision() {
        val recording = recording(rain)
        recording.succeeded(200, rainRevision())
        recording.failed(300)
        recording.succeeded(400, rainRevision())

        val detail = recording.close(500, null)
        val (old, new) = detail.suggestions.partition { it.attempt == 0 }
        assertTrue(old.isNotEmpty() && old.all { it.decision == Decision.Superseded && it.decidedAt == 400L })
        assertTrue(new.isNotEmpty() && new.all { it.attempt == 2 && it.decision == Decision.Ignored })
    }

    @Test
    fun changesBeforeAnyAttemptAreIgnored() {
        val recording = recording(rain)
        recording.changed(rainRevision().acceptAll(EditKind.Fix))
        val detail = recording.close(300, "Com estàs amb la pluja?")
        assertTrue(detail.suggestions.isEmpty())

        val another = recording(rain)
        another.changed(rainRevision().acceptAll(EditKind.Fix))
        another.succeeded(200, rainRevision())
        assertTrue(another.close(300, null).suggestions.all { it.attempt == 0 && it.decision == Decision.Ignored })
    }

    @Test
    fun aReusedVerdictSaysWhereItCameFrom() {
        val recording = recording(rain)
        val session = recording.attempt(200, emptyList(), "{}", null, null, rainRevision(), "m", Verdict.Status.Ok, reusedFrom = "earlier")
        assertEquals("earlier", session.attempts.single().reusedFrom)
        assertEquals(Verdict.Status.Ok, session.status)
        assertNull(recording(rain).succeeded(200, rainRevision()).attempts.single().reusedFrom)
    }

    @Test
    fun theStatusIsTheLastSuccessfulAttempts() {
        val recording = recording(rain)
        recording.attempt(200, emptyList(), "{}", null, null, null, null, Verdict.Status.Unclear)
        assertEquals(Verdict.Status.Unclear, recording.session.status)
        recording.failed(300)
        assertEquals(Verdict.Status.Unclear, recording.session.status)
        recording.attempt(400, emptyList(), "{}", null, null, rainRevision(), "m", Verdict.Status.Ok)
        assertEquals(Verdict.Status.Ok, recording.close(500, null).session.status)
    }

    @Test
    fun onlyAFailedAttemptFailsWithNoSuggestions() {
        val recording = recording(rain)
        recording.failed(200)
        val detail = recording.close(300, null)
        assertEquals(Outcome.Failed, detail.session.outcome)
        assertTrue(detail.suggestions.isEmpty())
    }

    @Test
    fun closingWhileTheFirstCheckRunsRecordsNothing() {
        val detail = recording(rain).close(300, null)
        assertEquals(Outcome.None, detail.session.outcome)
        assertTrue(detail.session.attempts.isEmpty())
        assertTrue(detail.suggestions.isEmpty())
    }

    @Test
    fun aResultWithNothingToReviewHasNoSuggestions() {
        val recording = recording(rain)
        recording.succeeded(200, null, meaning = null)
        val detail = recording.close(300, null)
        assertEquals(Outcome.None, detail.session.outcome)
        assertNull(detail.session.meaning)
        assertTrue(detail.suggestions.isEmpty())
    }

    @Test
    fun aReadOnlySelectionEndsCopiedOrIgnored() {
        val recording = recording(shower)
        recording.succeeded(200, showerRevision())
        recording.copied(EditKind.Fix)

        val detail = recording.close(300, null)
        assertEquals(Outcome.Copied, detail.session.outcome)
        assertEquals(setOf(Decision.Copied), detail.decisions(EditKind.Fix))
        assertEquals(setOf(Decision.Ignored), detail.decisions(EditKind.Natural))
    }

    @Test
    fun closingTwiceReturnsTheSameResult() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)

        val first = recording.close(300, fixed.workingText)
        recording.changed(fixed.undo())
        recording.copied(EditKind.Natural)
        val second = recording.close(900, null)
        assertSame(first, second)
        assertEquals(first.session, recording.session)
        assertEquals(300L, recording.session.closedAt)
    }

    @Test
    fun eachAttemptKeepsTheLanguageItAskedForAndTheSessionTheLastOne() {
        val recording = recording(rain)
        val settings = context.opening.settings
        recording.succeeded(200, rainRevision())
        recording.changeLanguage("es", settings, 250)
        // The session takes the language with the attempt.
        assertEquals("ca", recording.session.requestedLanguage)
        assertEquals("es", recording.succeeded(300, rainRevision()).requestedLanguage)
        recording.changeLanguage(null, settings.copy(model = "b-model"), 350)
        recording.failed(400)

        val detail = recording.close(500, null)
        assertEquals(listOf("ca", "es", null), detail.session.attempts.map { it.requestedLanguage })
        assertEquals(listOf("a-model", "a-model", "b-model"), detail.session.attempts.map { it.model })
        assertNull(detail.session.requestedLanguage)
        assertEquals("b-model", detail.session.model)
        assertEquals(listOf("ca", "es", null), detail.session.attempts.indices.map { detail.session.reuseKeyOf(it)!!.requestedLanguage })
        assertEquals(detail.session.reuseKey, detail.session.reuseKeyOf(2))
        assertEquals(Outcome.Failed, detail.session.outcome)
    }

    @Test
    fun anotherLanguageSupersedesTheRevisionOnOfferWhateverComesNext() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        recording.copied(EditKind.Fix)
        recording.changeLanguage("es", context.opening.settings, 300)
        // The new language's check failed: the card shows the failure, not the old suggestions.
        recording.failed(400)
        // A late report about the old card decides nothing.
        recording.changed(fixed.undo())

        val detail = recording.close(500, null)
        assertTrue(detail.suggestions.isNotEmpty())
        assertTrue(detail.suggestions.all { it.attempt == 0 && it.decision == Decision.Superseded && it.decidedAt == 300L })
        // The copy still happened on this card.
        assertEquals(Outcome.Copied, detail.session.outcome)
    }

    @Test
    fun theNewLanguagesRevisionIsDecidedAtTheClose() {
        val recording = recording(rain)
        recording.succeeded(200, rainRevision())
        recording.changeLanguage("es", context.opening.settings, 300)
        val revision = rainRevision()
        recording.succeeded(400, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)

        val detail = recording.close(500, fixed.workingText)
        val (old, new) = detail.suggestions.partition { it.attempt == 0 }
        assertTrue(old.isNotEmpty() && old.all { it.decision == Decision.Superseded && it.decidedAt == 300L })
        assertEquals(Decision.Accepted, new.single { it.kind == EditKind.Fix }.decision)
        assertTrue(new.all { it.attempt == 1 })
        assertEquals(1, detail.decidedAttempt())
        assertEquals("es", detail.session.requestedLanguage)
    }

    @Test
    fun otherSettingsAreRecordedWithTheNextAttemptWithoutStartingOver() {
        val recording = recording(rain)
        val revision = rainRevision()
        recording.succeeded(200, revision)
        val fixed = revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        recording.runWith("ca", context.opening.settings.copy(punctuation = "strict"))
        // A re-check with new settings carries the accepted fix over, as any re-check.
        recording.succeeded(300, rainRevision().acceptMatching(fixed.acceptedEdits))
        val detail = recording.close(400, fixed.workingText)
        assertEquals(listOf("Relaxed", "strict"), detail.session.attempts.map { it.punctuation })
        assertEquals("strict", detail.session.punctuation)
        assertTrue(detail.suggestions.filter { it.attempt == 0 }.all { it.decision == Decision.Superseded })
        assertEquals(Decision.Accepted, detail.suggestions.single { it.attempt == 1 && it.kind == EditKind.Fix }.decision)
    }

    @Test
    fun aClosedSessionDoesntChange() {
        val recording = recording(rain)
        recording.close(200, null)
        recording.changeLanguage("es", context.opening.settings, 300)
        recording.runWith("es", context.opening.settings.copy(model = "b-model"))
        assertEquals("ca", recording.session.requestedLanguage)
        assertEquals("a-model", recording.session.model)
    }

    @Test
    fun rowsFromBeforeSchema4RanEveryAttemptWithTheSessionsSettings() {
        val old = recording(rain).session.copy(schema = 3, attempts = listOf(Attempt(at = 1), Attempt(at = 2)))
        assertEquals(listOf(old.reuseKey, old.reuseKey), old.attempts.indices.map(old::reuseKeyOf))
        // From schema 4 on, an attempt without its settings matches nothing.
        assertNull(old.copy(schema = 4).reuseKeyOf(0))
    }
}
