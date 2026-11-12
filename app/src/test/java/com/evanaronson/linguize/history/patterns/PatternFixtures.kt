package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SuggestionRecord

/** Saved checks for pattern tests: one fix row per [Fix], all on one successful attempt. */
internal object PatternFixtures {
    const val DAY = 86_400_000L

    data class Fix(
        val from: String,
        val to: String,
        val why: String? = null,
        val decision: Decision = Decision.Accepted,
        val kind: EditKind = EditKind.Fix,
        val attempt: Int = 0,
    )

    fun check(
        id: String,
        day: Int,
        vararg fixes: Fix,
        text: String = "text of $id",
        language: String? = "es",
        reusedFrom: String? = null,
        attempts: Int = 1,
    ): SessionDetail {
        val at = day * DAY
        val session = SessionRecord(
            id = id, deviceId = "device", createdAt = at, updatedAt = at, startedAt = at, closedAt = at + 1,
            origin = Origin.Menu, requestedLanguage = language, text = text, textHash = "hash:$text",
            finalText = "$text!", outcome = Outcome.Applied, punctuation = "moderate", judgments = "both",
            provider = "gemini", model = "m", promptHash = "p", appVersion = "1",
            attempts = List(attempts) { Attempt(at = at, raw = "{}", requestedLanguage = language, reusedFrom = reusedFrom) },
            targetLanguage = language,
        )
        val rows = fixes.mapIndexed { index, fix ->
            SuggestionRecord(
                id = "$id-s$index", sessionId = id, deviceId = "device", createdAt = at, updatedAt = at,
                attempt = fix.attempt, kind = fix.kind, start = index, end = index + fix.from.length,
                fromText = fix.from, toText = fix.to, why = fix.why, decision = fix.decision, decidedAt = at + 1,
            )
        }
        return SessionDetail(session, rows)
    }

    fun fix(from: String, to: String, why: String? = null) = Fix(from, to, why)
}
