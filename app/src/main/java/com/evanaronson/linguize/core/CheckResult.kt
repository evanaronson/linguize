package com.evanaronson.linguize.core

/** What a finished check found. */
sealed interface CheckResult {
    /** The model couldn't tell what the text means. */
    data object Unclear : CheckResult

    /** The text isn't in the language chosen in settings; [found] is what the model took it for. */
    data class WrongLanguage(val expected: String, val found: String? = null) : CheckResult

    /** Suggestions for the judgments that were asked for, in display order. */
    data class Reviewed(
        val revision: Revision,
        val kinds: List<EditKind>,
        /** What the model assumed where the text was ambiguous. */
        val assumptions: List<Assumption> = emptyList(),
        /** What the model understood the writer to mean, in the writer's native language. */
        val meaning: String = "",
        /** Judgments with proposed changes that could not be offered safely. */
        val withheld: Set<EditKind> = emptySet(),
    ) : CheckResult {
        /** No changes were needed, rather than proposed changes being discarded. */
        val looksGood get() = withheld.isEmpty() && kinds.all { revision.edits(it).isEmpty() }

        /** Every judgment is complete and every suggestion has been accepted or overtaken. */
        val isResolved get() = withheld.isEmpty() && kinds.all { revision.remaining(it).isEmpty() }
    }
}

/**
 * Turns the model's [verdict] into edits of [original]. Fixes and rewordings are
 * both anchored to the original text, so either can be accepted on its own;
 * a "change" that changes nothing produces no edit, and one that adds a link
 * isn't offered (see [Links]).
 */
fun interpret(
    original: String,
    verdict: Verdict,
    judgments: Judgments = Judgments.Both,
    expectedLanguage: String? = null,
): CheckResult {
    when (verdict.status) {
        Verdict.Status.Unclear -> return CheckResult.Unclear
        Verdict.Status.WrongLanguage ->
            return expectedLanguage?.let { CheckResult.WrongLanguage(it, verdict.language.trim().ifEmpty { null }) } ?: CheckResult.Unclear
        Verdict.Status.Ok -> Unit
    }

    // A contradictory "ok" status must not override the detected language.
    val foundLanguage = verdict.language.trim()
    if (expectedLanguage != null && foundLanguage.isNotEmpty() &&
        !foundLanguage.substringBefore('(').trim().equals(expectedLanguage, ignoreCase = true)) {
        return CheckResult.WrongLanguage(expectedLanguage, foundLanguage)
    }

    val withheld = mutableSetOf<EditKind>()
    val fixResult = verdict.corrected.trim()
        .takeIf { EditKind.Fix in judgments.kinds && verdict.hasErrors && it.isNotEmpty() }
        ?.let { Edits.fixes(original, it, verdict.fixes) }
    val proposedFixes = fixResult?.edits.orEmpty()
    // Rewordings must be checked against the corrections the writer can actually
    // accept. A discarded fix cannot silently become part of their source text.
    val fixes = Links.withoutNew(original, proposedFixes)
    if (fixes.size != proposedFixes.size) withheld += EditKind.Fix
    if (EditKind.Fix in judgments.kinds && verdict.hasErrors &&
        (verdict.corrected.isBlank() || fixResult?.rejected == true)) {
        withheld += EditKind.Fix
    }
    val naturals = verdict.natural.trim()
        .takeIf { EditKind.Natural in judgments.kinds && verdict.moreNatural && it.isNotEmpty() }
        ?.let { Edits.naturalResult(original, fixes, it, verdict.naturalChanges, firstId = proposedFixes.size) }
    if (EditKind.Natural in judgments.kinds && verdict.moreNatural &&
        (verdict.natural.isBlank() || naturals?.rejected == true)) {
        withheld += EditKind.Natural
    }
    val proposed = fixes + naturals?.edits.orEmpty()
    val safe = Links.withoutNew(original, proposed)
    withheld += (proposed - safe.toSet()).map { it.kind }

    // Keep references attached to their original assumption even when a malformed
    // assumption is omitted. Neither index gaps nor an invalid reference can retarget it.
    val assumptions = verdict.assumptions.withIndex().filter { it.value.about.isNotBlank() && it.value.assumed.isNotBlank() }
    val indices = assumptions.mapIndexed { index, value -> value.index to index }.toMap()
    val linked = safe.map { edit -> edit.copy(assumptionIndices = edit.assumptionIndices.mapNotNull(indices::get).distinct()) }
    return CheckResult.Reviewed(
        Revision(original, linked),
        kinds = EditKind.entries.filter { it in judgments.kinds },
        assumptions = assumptions.map { it.value },
        meaning = verdict.meaning.trim().takeUnless { isUntranslated(it, original) }.orEmpty(),
        withheld = withheld,
    )
}

/**
 * Whether [meaning] is mostly [original]'s own words: the model echoed the text
 * instead of translating it, so there's no meaning worth showing.
 */
internal fun isUntranslated(meaning: String, original: String): Boolean {
    fun words(text: String) = Regex("""\p{L}{3,}""").findAll(text.lowercase()).map { it.value }.toList()
    val meaningWords = words(meaning)
    if (meaningWords.isEmpty()) return false
    val originalWords = words(original).toSet()
    return meaningWords.count { it in originalWords } * 2 >= meaningWords.size
}
