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
    ) : CheckResult {
        /** Nothing to suggest for any judgment that was made. */
        val looksGood get() = kinds.all { revision.edits(it).isEmpty() }

        /** Every suggestion has been accepted or overtaken. */
        val isResolved get() = kinds.all { revision.remaining(it).isEmpty() }
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

    val fixes = verdict.corrected.trim()
        .takeIf { EditKind.Fix in judgments.kinds && verdict.hasErrors && it.isNotEmpty() }
        ?.let { Edits.fixes(original, it, verdict.fixes) }
        .orEmpty()
    val naturals = verdict.natural.trim()
        .takeIf { EditKind.Natural in judgments.kinds && verdict.moreNatural && it.isNotEmpty() }
        ?.let { Edits.naturals(original, fixes, it, verdict.naturalChanges, firstId = fixes.size) }
        .orEmpty()

    return CheckResult.Reviewed(
        Revision(original, Links.withoutNew(original, fixes + naturals)),
        kinds = EditKind.entries.filter { it in judgments.kinds },
        assumptions = verdict.assumptions.filter { it.about.isNotBlank() && it.assumed.isNotBlank() },
        meaning = verdict.meaning.trim().takeUnless { isUntranslated(it, original) }.orEmpty(),
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
