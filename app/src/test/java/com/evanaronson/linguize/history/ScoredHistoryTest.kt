package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scores are evidence about exact suggestions, not acceptance identity or inferred historical data. */
class ScoredHistoryTest {
    private val fixture = HistorySchemaTest()
    private val profile = WritingProfile.defaultFor(Language.Catalan)
    private val rated = SuggestionAssessment(0, 3)

    @Test
    fun profilesAndAssessmentRoundTripAsStructuredExportWithoutTurningNullIntoZero() {
        val session = fixture.session.copy(writingProfiles = listOf(profile))
        assertEquals(session, sessionRecord(MapRow(session.values())))
        val scored = fixture.suggestion.copy(assessment = rated)
        assertEquals(scored, suggestionRecord(MapRow(scored.values())))
        val json = rowJson(MapRow(scored.values()), HistorySchema.SUGGESTION_COLUMNS)
        assertEquals("0", json["assessment"]!!.jsonObject["confidence"].toString())
        assertEquals("3", json["assessment"]!!.jsonObject["impact"].toString())
        assertEquals("generated", decodeAssessment(encodeAssessment(rated))!!.origin)
        assertEquals("null", rowJson(MapRow(fixture.suggestion.values()), HistorySchema.SUGGESTION_COLUMNS)["assessment"].toString())
        assertEquals(profile.language, decodeProfiles(rowJson(MapRow(session.values()), HistorySchema.SESSION_COLUMNS)["writingProfiles"].toString()).single().language)
        assertEquals(1, rowJson(MapRow(session.values()), HistorySchema.SESSION_COLUMNS)["writingProfiles"]!!.jsonArray.size)
    }

    @Test
    fun corruptOrMissingAssessmentNeverBecomesAScore() {
        for (value in listOf(null, "broken", "{}", """{"confidence":4,"impact":1}""", """{"confidence":1}""")) {
            assertNull(value, decodeAssessment(value))
            assertNull(suggestionRecord(MapRow(fixture.suggestion.values() + ("assessment" to value)))!!.assessment)
        }
        assertTrue(decodeProfiles(null).isEmpty())
        assertTrue(decodeProfiles("broken").isEmpty())
        assertEquals(rated.copy(origin = "retrospective"), decodeAssessment(encodeAssessment(rated.copy(origin = "retrospective"))))
    }

    @Test
    fun eachAttemptRetainsItsOwnProfileSnapshotAndCacheKey() {
        val before = fixture.session.settings.copy(writingProfiles = listOf(profile))
        val after = before.copy(writingProfiles = listOf(profile.copy(register = WritingRegister.Formal)))
        val context = SessionContext(Opening(Origin.Menu, null, "ca", before), "v1", "device")
        val recording = SessionRecording("Com estas?", context, 1)
        assertEquals(listOf(profile), recording.session.writingProfiles)
        recording.attempt(2, emptyList(), "{}", null, null, null, null)
        recording.runWith("ca", after)
        recording.attempt(3, emptyList(), "{}", null, null, null, null)
        val session = recording.session
        assertEquals(before, session.reuseKeyOf(0)!!.settings)
        assertEquals(after, session.reuseKeyOf(1)!!.settings)
        assertEquals(after.writingProfiles, session.writingProfiles)
        val roundTrip = sessionRecord(MapRow(session.values()))
        assertEquals(session, roundTrip)
        assertNotEquals(HistorySchema.reusableArgs(session.reuseKeyOf(0)!!, 0), HistorySchema.reusableArgs(session.reuseKeyOf(1)!!, 0))
        assertTrue(HistorySchema.reusable(session.reuseKey).contains("\"writingProfiles\" = ?"))
        assertNull(SessionDetail(session, listOf(fixture.suggestion.copy(attempt = 0))).keptFor(session.reuseKey))
        val old = fixture.session.copy(attempts = listOf(Attempt(1, raw = "{}", punctuation = "moderate", judgments = "both", provider = "gemini", model = "m", promptHash = "p1")))
        assertTrue(old.reuseKeyOf(0)!!.settings.writingProfiles.isEmpty())
    }

    @Test
    fun recordedRowsOwnRatingsEvenWhenModernEngineMatchesOrRemapsEdits() {
        val row = fixture.suggestion
        val edit = Edit(7, row.kind, row.start, row.end, row.fromText, row.toText, row.why, assessment = SuggestionAssessment(3, 1))
        val today = Revision(fixture.session.text, listOf(edit))
        // A legacy row cannot acquire the modern parser's scores.
        assertNull(recorded(today, listOf(row)).edits.single().assessment)
        val scoredRow = row.copy(assessment = rated)
        val exact = recorded(today, listOf(scoredRow))
        assertEquals(rated, exact.edits.single().assessment)
        assertTrue(exact.isApplied(7))
        val remapped = recorded(Revision(fixture.session.text, emptyList()), listOf(scoredRow))
        assertEquals(rated, remapped.edits.single().assessment)
        assertEquals(exact.workingText, remapped.workingText)
    }

    @Test
    fun scoreOnlyMetadataUpdatesDoNotEraseAcceptanceRecording() {
        val context = SessionContext(Opening(Origin.Menu, null, "ca", fixture.session.settings), "v1", "device")
        val recording = SessionRecording("estas", context, 1)
        val edit = Edit(0, EditKind.Fix, 0, 5, "estas", "estàs", why = null, assessment = rated)
        val revision = Revision("estas", listOf(edit))
        recording.attempt(2, emptyList(), "{}", null, null, revision, null)
        val accepted = Revision("estas", listOf(edit.copy(assessment = SuggestionAssessment(3, 1)))).accept(0)
        recording.changed(accepted)
        val stored = recording.close(3, accepted.workingText).suggestions.single()
        assertEquals(Decision.Accepted, stored.decision)
        assertEquals(SuggestionAssessment(3, 1), stored.assessment)
    }
}
