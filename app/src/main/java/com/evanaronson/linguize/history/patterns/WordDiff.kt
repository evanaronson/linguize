package com.evanaronson.linguize.history.patterns

import java.text.Normalizer

/*
 * Small, deterministic text comparisons the pattern analyser is built on. No model, no
 * network, no Android: the same pair always compares the same way.
 */

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’](?=\\p{L}))?|[\\p{L}\\p{M}\\p{N}]+['’]")
private val SPACES = Regex("\\s+")

/** Lowercase, without accents or other combining marks: "Está" and "esta" fold the same. */
internal fun fold(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

/** Letters and digits only, in order: what is left when punctuation and spacing are ignored. */
internal fun lettersOnly(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFC).filter { it.isLetterOrDigit() }

/** The pair as a writer would recognise it again: trimmed, single spaces, lowercase. */
internal fun normalizePair(text: String): String = SPACES.replace(text.trim(), " ").lowercase()

/**
 * Lowercase words, in order. An elided clitic keeps its apostrophe and stands alone, so
 * Catalan "l'home" is `l'` and `home`, and "d'acord" is `d'` and `acord`.
 */
internal fun words(text: String): List<String> =
    WORD.findAll(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC).replace('’', '\''))
        .map { it.value }.toList()

/** The words only one side has, after keeping the longest run both share, in order. */
internal data class WordChange(val removed: List<String>, val added: List<String>) {
    val changed: List<String> get() = removed + added
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
}

/** A word-level difference by longest common subsequence. */
internal fun wordChange(from: List<String>, to: List<String>): WordChange {
    val n = from.size
    val m = to.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (from[i] == to[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val removed = mutableListOf<String>()
    val added = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            from[i] == to[j] -> { i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> removed += from[i++]
            else -> added += to[j++]
        }
    }
    while (i < n) removed += from[i++]
    while (j < m) added += to[j++]
    return WordChange(removed, added)
}

/** Edit distance between two short strings. */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
