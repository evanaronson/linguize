package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.SessionDetail
import java.security.MessageDigest

/*
 * "Your patterns": learning derived from History, kept apart from it.
 *
 * The analyser reads saved checks and returns a [PatternReport]. It never writes History
 * and stores nothing itself; the report is rebuilt from whatever History holds now, so a
 * deleted check disappears from it on the next build. Each report names the analyser
 * version, the sessions, attempts and suggestions it used, and how each change was grouped
 * (a fact about the text, or an inference). See docs/lab/patterns.md.
 */

/** One accepted change the report used, with the facts recorded for it. */
data class PatternExample(
    val sessionId: String,
    val suggestionId: String,
    val attempt: Int,
    /** When the check started. */
    val at: Long,
    /** The ISO code the change's attempt targeted; null when unknown. */
    val language: String?,
    val from: String,
    val to: String,
    val why: String?,
    val assessment: SuggestionAssessment?,
    val editKind: EditKind,
    val kind: PatternKind,
    val basis: Basis,
    /** History's origin token of the check it came from ("chat" for Lab Chat); null when unknown. */
    val origin: String? = null,
)

/** The same change seen more than once, as the writer would recognise it. */
data class PairCount(val from: String, val to: String, val count: Int)

/** Whether a group shows up in a smaller share of recent checks than earlier ones. */
enum class Trend(val token: String) {
    Fewer("fewer"),
    Steady("steady"),
    More("more"),

    /** Too few checks or occurrences to compare. */
    Unknown("unknown"),
}

/** A group of changes the writer accepted, with the examples it came from. */
data class Pattern(
    val kind: PatternKind,
    /** Newest first. */
    val examples: List<PatternExample>,
    /** Distinct checks with at least one example. */
    val checks: Int,
    val trend: Trend,
    /** Occurrences per equal span of time from [PatternReport.firstAt] to [PatternReport.lastAt], oldest first. */
    val buckets: List<Int>,
    /** Repeated before/after pairs, most frequent first. */
    val pairs: List<PairCount>,
) {
    val occurrences: Int get() = examples.size

    /** Every way its examples were grouped. */
    val bases: Set<Basis> get() = examples.mapTo(linkedSetOf()) { it.basis }

    /** True when any example's group is an inference rather than a fact about the text. */
    val inferred: Boolean get() = bases.any { it != Basis.Exact }
}

/** Which recorded rows one counted check contributed. */
data class PatternSource(val sessionId: String, val attempt: Int, val suggestionIds: List<String>)

/** Everything derived from one read of History. */
data class PatternReport(
    val analyser: String,
    /** Saved checks read. */
    val checksRead: Int,
    /** Checks counted after leaving out reused answers and repeated texts. */
    val checksCounted: Int,
    /** Checks left out because their answer was reused from another check. */
    val reused: Int,
    /** Checks left out because a newer check had the same text. */
    val repeats: Int,
    val firstAt: Long?,
    val lastAt: Long?,
    /** Every counted check with the accepted changes it contributed (possibly none). */
    val sources: List<PatternSource>,
    /** Groups seen in at least [PatternAnalyser.MIN_CHECKS] checks, most frequent first. */
    val patterns: List<Pattern>,
    /** Groups seen in a single check, and changes no rule recognised. */
    val unpatterned: List<PatternExample>,
    /** Stable identity of the evidence and analyser: changes when either does. */
    val fingerprint: String,
) {
    /** Checks that contributed at least one example. */
    val evidenceSessions: Set<String>
        get() = sources.filter { it.suggestionIds.isNotEmpty() }.mapTo(linkedSetOf()) { it.sessionId }

    /** Enough to show on Home: a few counted checks and one recurring group. */
    val worthShowing: Boolean get() = checksCounted >= PatternAnalyser.MIN_COUNTED && patterns.isNotEmpty()

    fun pattern(kind: PatternKind): Pattern? = patterns.firstOrNull { it.kind == kind }
}

/**
 * Builds a [PatternReport] from saved checks. Pure: no clock, storage or network.
 *
 * Counting rules:
 * - Evidence is a change whose recorded decision is [Decision.Accepted]: the writer accepted
 *   it and it was put back. Ignored, undone, copied and superseded rows are not evidence of
 *   a mistake, and a retired fix was replaced by a rewording.
 * - A check whose decided answer was reused from another check is left out: it is not an
 *   independent observation.
 * - When the same text was checked more than once, only the newest check counts.
 * - A rewording counts only when a specific rule recognises it (see [PatternRules]).
 */
object PatternAnalyser {
    /**
     * Bump when rules or counting change, so saved explanations built on the old analysis
     * are dropped. v1: first rules.
     */
    const val VERSION = "patterns-v1"

    /** A group needs this many distinct checks to be a pattern. */
    const val MIN_CHECKS = 2

    /** Counted checks before a report is worth showing on Home. */
    const val MIN_COUNTED = 3

    /** Counted checks, and checks with the group, before a trend is offered. */
    const val TREND_MIN_COUNTED = 8
    const val TREND_MIN_CHECKS = 3

    /** Smallest change in the share of checks that counts as fewer or more. */
    const val TREND_THRESHOLD = 0.15

    const val BUCKETS = 6

    fun analyse(details: List<SessionDetail>): PatternReport {
        val newestFirst = details.sortedWith(compareByDescending<SessionDetail> { it.session.startedAt }.thenBy { it.session.id })
        var reused = 0
        var repeats = 0
        val seenTexts = mutableSetOf<String>()
        val counted = mutableListOf<Pair<SessionDetail, Int?>>()
        for (detail in newestFirst) {
            val decided = detail.decidedAttempt()
            val attempt = decided?.let { detail.session.attempts.getOrNull(it) }
            if (attempt?.reusedFrom != null) {
                reused++
                continue
            }
            if (!seenTexts.add(detail.session.textHash)) {
                repeats++
                continue
            }
            counted += detail to decided
        }

        val examples = mutableListOf<PatternExample>()
        val sources = counted.map { (detail, decided) ->
            val session = detail.session
            val used = detail.suggestions
                .filter { it.decision == Decision.Accepted }
                .sortedWith(compareBy({ it.attempt }, { it.start }, { it.id }))
                .mapNotNull { row ->
                    val attempt = session.attempts.getOrNull(row.attempt)
                    val language = attempt?.targetLanguage ?: session.targetLanguage
                    val group = PatternRules.classify(row.fromText, row.toText, row.why, language, row.kind) ?: return@mapNotNull null
                    PatternExample(
                        sessionId = session.id, suggestionId = row.id, attempt = row.attempt, at = session.startedAt,
                        language = language, from = row.fromText, to = row.toText, why = row.why,
                        assessment = row.assessment, editKind = row.kind, kind = group.kind, basis = group.basis,
                        origin = session.origin?.token,
                    ).also(examples::add)
                }
            PatternSource(session.id, decided ?: used.firstOrNull()?.attempt ?: -1, used.map { it.suggestionId })
        }

        val times = counted.map { it.first.session.startedAt }
        val firstAt = times.minOrNull()
        val lastAt = times.maxOrNull()
        val groups = examples.filter { it.kind != PatternKind.Other }.groupBy { it.kind }
        val patterns = groups
            .filterValues { list -> list.map { it.sessionId }.distinct().size >= MIN_CHECKS }
            .map { (kind, list) ->
                Pattern(
                    kind = kind,
                    examples = list.sortedWith(compareByDescending<PatternExample> { it.at }.thenBy { it.suggestionId }),
                    checks = list.map { it.sessionId }.distinct().size,
                    trend = trend(list, times),
                    buckets = buckets(list, firstAt, lastAt),
                    pairs = pairs(list),
                )
            }
            .sortedWith(compareByDescending<Pattern> { it.checks }.thenByDescending { it.occurrences }.thenBy { it.kind.ordinal })
        val grouped = patterns.flatMap { it.examples }.mapTo(hashSetOf()) { it.suggestionId }
        val unpatterned = examples.filter { it.suggestionId !in grouped }
            .sortedWith(compareByDescending<PatternExample> { it.at }.thenBy { it.suggestionId })

        return PatternReport(
            analyser = VERSION,
            checksRead = details.size,
            checksCounted = counted.size,
            reused = reused,
            repeats = repeats,
            firstAt = firstAt,
            lastAt = lastAt,
            sources = sources,
            patterns = patterns,
            unpatterned = unpatterned,
            fingerprint = fingerprint(sources),
        )
    }

    /**
     * Compares the share of checks with this group in the older and newer halves of the
     * counted checks. A share, not a count, so checking more often doesn't read as "more".
     */
    internal fun trend(examples: List<PatternExample>, checkTimes: List<Long>): Trend {
        val withPattern = examples.map { it.sessionId to it.at }.distinct()
        if (checkTimes.size < TREND_MIN_COUNTED || withPattern.size < TREND_MIN_CHECKS) return Trend.Unknown
        val sorted = checkTimes.sorted()
        val split = sorted[sorted.size / 2]
        val older = sorted.count { it < split }
        val newer = sorted.size - older
        if (older == 0 || newer == 0) return Trend.Unknown
        val olderShare = withPattern.count { it.second < split }.toDouble() / older
        val newerShare = withPattern.count { it.second >= split }.toDouble() / newer
        return when {
            newerShare <= olderShare - TREND_THRESHOLD -> Trend.Fewer
            newerShare >= olderShare + TREND_THRESHOLD -> Trend.More
            else -> Trend.Steady
        }
    }

    internal fun buckets(examples: List<PatternExample>, firstAt: Long?, lastAt: Long?): List<Int> {
        if (firstAt == null || lastAt == null) return emptyList()
        val counts = IntArray(BUCKETS)
        val span = (lastAt - firstAt).coerceAtLeast(1)
        for (example in examples) {
            val index = (((example.at - firstAt).toDouble() / span) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            counts[index]++
        }
        return counts.toList()
    }

    private fun pairs(examples: List<PatternExample>): List<PairCount> = examples
        .groupBy { normalizePair(it.from) to normalizePair(it.to) }
        .map { (key, list) -> PairCount(key.first, key.second, list.size) }
        .sortedWith(compareByDescending<PairCount> { it.count }.thenBy { it.from }.thenBy { it.to })

    /** Identifies the analyser and the exact rows it used. */
    internal fun fingerprint(sources: List<PatternSource>): String {
        val text = buildString {
            append(VERSION)
            sources.sortedBy { it.sessionId }.forEach { source ->
                append('\n').append(source.sessionId).append(':').append(source.attempt)
                source.suggestionIds.sorted().forEach { append(',').append(it) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
