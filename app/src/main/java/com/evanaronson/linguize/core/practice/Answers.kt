package com.evanaronson.linguize.core.practice

import java.text.Normalizer

/** How a typed or picked answer compares with the fix. */
sealed interface AnswerResult {
    /** The fix, allowing for spacing, typographic quotes and (unless the fix was about them) capitals. */
    data object Correct : AnswerResult

    /** Nearly the fix: one kind of slip, named so the writer can see it. */
    data class Almost(val hint: Hint) : AnswerResult

    /** The mistake as originally written. */
    data object Unchanged : AnswerResult

    data object Wrong : AnswerResult

    /** How the answer counts for scheduling. */
    val grade: Grade
        get() = when (this) {
            Correct -> Grade.Good
            is Almost -> Grade.Hard
            Unchanged, Wrong -> Grade.Again
        }
}

/** What was nearly right. [word] is the expected word, when one can be named. */
sealed interface Hint {
    /** The expected word has an accent the answer lacks ("despues" for "después"). */
    data class MissingAccent(val word: String) : Hint

    /** The answer has an accent the expected word doesn't ("cómo" for "como"). */
    data class ExtraAccent(val word: String) : Hint

    /** Both have an accent, but not the same one ("què" for "qué"), or on another letter. */
    data class WrongAccent(val word: String) : Hint

    /** Accents differ in more than one way, or words couldn't be paired. */
    data object Accents : Hint

    data object Punctuation : Hint
    data object Capitals : Hint

    /** One letter off, and closer to the fix than the original was. */
    data object Spelling : Hint
}

/**
 * Local answer checking: no model, so it works offline. Comparison is on NFC text with
 * spacing collapsed and typographic apostrophes and quotes made plain.
 */
object Answers {
    /** Checks a typed replacement for [item]'s highlighted words; the whole corrected sentence is accepted too. */
    fun check(item: PracticeItem, answer: String): AnswerResult {
        val result = check(original = item.wrong, expected = item.right, answer = answer)
        if (result == AnswerResult.Correct) return result
        val whole = check(original = item.sentence, expected = item.corrected, answer = answer)
        return if (whole == AnswerResult.Correct) whole else result
    }

    fun check(original: String, expected: String, answer: String): AnswerResult {
        val a = normalize(answer)
        val e = normalize(expected)
        val o = normalize(original)
        if (a == e) return AnswerResult.Correct
        // When capitals were the fix, they count; otherwise they don't.
        val capitalsMatter = o != e && o.equals(e, ignoreCase = true)
        if (a.equals(e, ignoreCase = true)) {
            return when {
                !capitalsMatter -> AnswerResult.Correct
                a == o -> AnswerResult.Unchanged
                else -> AnswerResult.Almost(Hint.Capitals)
            }
        }
        if (a == o || (!capitalsMatter && a.equals(o, ignoreCase = true))) return AnswerResult.Unchanged

        val aBare = lettersOnly(a).lowercase()
        val eBare = lettersOnly(e).lowercase()
        if (aBare == eBare) return AnswerResult.Almost(Hint.Punctuation)
        if (stripAccents(aBare) == stripAccents(eBare)) return AnswerResult.Almost(accentHint(a, e))

        val toFix = distance(a.lowercase(), e.lowercase())
        if (e.length >= SPELLING_MIN_LENGTH && toFix == 1 && distance(o.lowercase(), e.lowercase()) > toFix) {
            return AnswerResult.Almost(Hint.Spelling)
        }
        return AnswerResult.Wrong
    }

    private const val SPELLING_MIN_LENGTH = 5

    /** NFC, plain apostrophes and quotes, single spaces, trimmed. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace(apostrophes, "'")
        .replace(quotes, "\"")
        .replace(spaces, " ")
        .trim()

    /** Letters and digits in words separated by single spaces: punctuation and spacing removed. */
    fun lettersOnly(text: String): String = words(text).joinToString(" ")

    /** The words of [text], without punctuation. An apostrophe or hyphen inside a word keeps it whole. */
    fun words(text: String): List<String> = wordPattern.findAll(normalize(text)).map { it.value }.toList()

    /** Letters without their accents or other combining marks; ç, ñ and l·l become c, n and ll. */
    fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").replace("·", "")

    private fun accentHint(answer: String, expected: String): Hint {
        val got = words(answer)
        val want = words(expected)
        if (got.size != want.size) return Hint.Accents
        val differing = got.indices.filter { !got[it].equals(want[it], ignoreCase = true) }
        val index = differing.singleOrNull() ?: return Hint.Accents
        val word = want[index]
        val have = got[index]
        val wantAccents = hasAccents(word)
        val haveAccents = hasAccents(have)
        return when {
            !haveAccents && wantAccents -> Hint.MissingAccent(word)
            !wantAccents && haveAccents -> Hint.ExtraAccent(word)
            else -> Hint.WrongAccent(word)
        }
    }

    /** Whether a composed letter carries an accent or other combining mark. */
    private fun hasAccents(word: String): Boolean {
        val composed = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC)
        return composed.any { stripAccents(it.toString()) != it.toString() }
    }

    /** Levenshtein distance, for the one-letter slip. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val apostrophes = Regex("[\u2018\u2019\u02BC\u00B4`]")
    private val quotes = Regex("[\u201C\u201D\u00AB\u00BB\u201E]")
    private val spaces = Regex("\\s+")
    private val marks = Regex("\\p{M}+")
    private val wordPattern = Regex("[\\p{L}\\p{N}]+(?:['\\-\u00B7][\\p{L}\\p{N}]+)*")
}
