package com.evanaronson.linguize.history.corpus

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SuggestionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab: a gap the check filled is an accepted change, so its span is corrected: not evidence the writer knew the words. */
class GapsCorpusSourceTest {
    private val text = "¿Puedes [pick me up] a las 8? Tengo que drop off las llaves"

    private fun session(text: String) = SessionRecord(
        id = "s", deviceId = "device", createdAt = 1, updatedAt = 1, startedAt = 1, closedAt = 1, origin = Origin.Menu,
        requestedLanguage = "es", text = text, textHash = SessionRecording.hash(text), finalText = text,
        outcome = Outcome.Applied, punctuation = "moderate", judgments = "both", provider = "gemini", model = "model",
        promptHash = "prompt-gabcd", appVersion = "1",
        attempts = listOf(Attempt(at = 1, raw = "{}", requestedLanguage = "es", targetLanguage = "es")),
    )

    private fun change(from: String, to: String, decision: Decision, kind: EditKind = EditKind.Fix) = text.indexOf(from).let { at ->
        SuggestionRecord(
            id = "$from>$to", sessionId = "s", deviceId = "device", createdAt = 1, updatedAt = 1, attempt = 0,
            kind = kind, start = at, end = at + from.length, fromText = from, toText = to, decision = decision, decidedAt = 1,
        )
    }

    private fun corrected(vararg changes: SuggestionRecord): List<String> =
        CheckUtterances.of(SessionDetail(session(text), changes.toList())).corrected.map { text.substring(it.start, it.end) }

    @Test
    fun acceptedFillsAreCorrectedSpansWithAndWithoutBrackets() {
        assertEquals(
            listOf("[pick me up]", "drop off"),
            corrected(change("[pick me up]", "recogerme", Decision.Accepted), change("drop off", "dejar", Decision.Accepted)),
        )
    }

    @Test
    fun aFillAcceptedAsARewordingIsCorrectedButAnOrdinaryRewordingIsNot() {
        assertEquals(
            listOf("[pick me up]", "drop off"),
            corrected(
                change("[pick me up]", "recogerme", Decision.Accepted, EditKind.Natural),
                change("drop off", "dejar", Decision.Accepted, EditKind.Natural),
                change("las llaves", "tus llaves", Decision.Accepted, EditKind.Natural),
            ),
        )
    }

    @Test
    fun aFillTheWriterDidNotAcceptLeavesTheirWords() {
        // The tokenizer still skips the brackets; the unbracketed words are the writer's to count.
        assertTrue(corrected(change("drop off", "dejar", Decision.Ignored)).isEmpty())
    }
}
