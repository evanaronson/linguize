package com.evanaronson.linguize.history.corpus

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.history.patterns.patternEvidence
import java.io.IOException

/**
 * Lab: the corpus source "check" (docs/lab/corpus.md). Each visible saved check gives its
 * original text exactly as the writer typed it, never the text that was put back. Read
 * through History's own APIs on demand, so a deleted check is gone from the next read.
 *
 * [notTyped] gives the text hashes ([com.evanaronson.linguize.history.SessionRecording.hash])
 * of texts Linguize wrote and then checked (filled gaps, a text said in another language):
 * those checks are left out.
 */
class CheckCorpusSource(
    private val history: HistoryStore,
    private val notTyped: suspend () -> Set<String> = { emptySet() },
    private val maxChecks: Int = MAX_CHECKS,
) : CorpusSource {
    override val name: String = NAME

    /** Throws when History couldn't be read, so the corpus skips it rather than reading it as empty. */
    override suspend fun utterances(): List<Utterance> {
        val details = history.patternEvidence(maxChecks, PAGE) ?: throw IOException("History couldn't be read")
        return CheckUtterances.from(details, runCatching { notTyped() }.getOrDefault(emptySet()))
    }

    companion object {
        const val NAME = "check"
        const val MAX_CHECKS = 3000
        private const val PAGE = 100
    }
}

/** The pure mapping from saved checks to utterances. */
object CheckUtterances {
    /** The utterance id of a saved check. */
    fun idOf(sessionId: String) = "${CheckCorpusSource.NAME}:$sessionId"

    /** The saved check an utterance id names, or null when it is from another source. */
    fun sessionOf(utteranceId: String): String? =
        utteranceId.takeIf { it.startsWith("${CheckCorpusSource.NAME}:") }?.substringAfter(':')?.takeIf { it.isNotEmpty() }

    /**
     * One utterance per saved check in [details], except:
     * - a check that reused another saved check's answer: the same text, counted once;
     * - a check of text that is exactly what an earlier check put back: Linguize's words, not the writer's;
     * - a check whose text hash is in [notTyped]: text Linguize wrote;
     * - a check from the app's own Try it card (never saved today).
     */
    fun from(details: List<SessionDetail>, notTyped: Set<String> = emptySet()): List<Utterance> {
        val present = details.map { it.session.id }.toSet()
        val ordered = details.sortedWith(compareBy({ it.session.startedAt }, { it.session.id }))
        val putBack = mutableSetOf<String>()
        val out = mutableListOf<Utterance>()
        for (detail in ordered) {
            val session = detail.session
            val reused = session.attempts.any { it.reusedFrom != null && it.reusedFrom in present }
            val recheckOfPutBack = session.text.trim() in putBack
            session.finalText?.let { putBack += it.trim() }
            if (reused || recheckOfPutBack || session.textHash in notTyped || session.origin == Origin.Tester) continue
            if (session.text.isBlank()) continue
            out += of(detail)
        }
        return out
    }

    /** A saved check's original text, with the spans of the fixes the writer accepted. */
    fun of(detail: SessionDetail): Utterance {
        val session = detail.session
        val decided = detail.decidedAttempt()
        val attempt = decided?.let { session.attempts.getOrNull(it) }
        val language = attempt?.detectedLanguage ?: attempt?.targetLanguage ?: session.detectedLanguage ?: session.targetLanguage
        return Utterance(
            id = idOf(session.id),
            source = CheckCorpusSource.NAME,
            language = language,
            text = session.text,
            corrected = corrected(detail, decided),
            at = session.startedAt,
        )
    }

    /**
     * Where the writer's words were wrong: each fix they accepted, and each fix a rewording
     * they accepted took over ([Decision.Retired]). An accepted rewording alone corrects
     * nothing, since the original was right, only less natural. Fixes undone, ignored or only
     * copied don't count: the writer kept their own words.
     */
    fun corrected(detail: SessionDetail, attempt: Int? = detail.decidedAttempt()): List<Span> {
        val text = detail.session.text
        val rows = detail.suggestions.filter { it.decision != Decision.Superseded && (attempt == null || it.attempt == attempt) }
        val rewordings = rows.filter { it.kind == EditKind.Natural && it.decision == Decision.Accepted }.mapNotNull { span(it, text) }
        return rows.filter { it.kind == EditKind.Fix }
            .mapNotNull { fix ->
                val span = span(fix, text) ?: return@mapNotNull null
                when (fix.decision) {
                    Decision.Accepted -> span
                    Decision.Retired -> span.takeIf { rewordings.any { it.overlaps(span.start, span.end) } }
                    else -> null
                }
            }
            .filter { it.end > it.start }
            .distinct()
            .sortedBy { it.start }
    }

    /** The suggestion's place in [text]; found by its words when the stored position is off. */
    private fun span(row: SuggestionRecord, text: String): Span? {
        if (row.start in 0..row.end && row.end <= text.length && text.substring(row.start, row.end) == row.fromText) {
            return Span(row.start, row.end)
        }
        if (row.fromText.isEmpty()) return null
        val at = text.indexOf(row.fromText)
        if (at < 0 || text.indexOf(row.fromText, at + 1) >= 0) return null
        return Span(at, at + row.fromText.length)
    }
}
