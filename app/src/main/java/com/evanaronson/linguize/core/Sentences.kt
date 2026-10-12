package com.evanaronson.linguize.core

/** Sentence boundaries for edit scope, independent of capitalisation in casual chat. */
internal object Sentences {
    fun ranges(text: String): List<IntRange> {
        val cuts = sortedSetOf<Int>()
        paragraphBreak.findAll(text).forEach { match ->
            val at = skipSpace(text, match.range.last + 1)
            if (at < text.length) cuts += at
        }
        for (index in text.indices) {
            val mark = text[index]
            if (mark !in ".!?…") continue
            if (index + 1 < text.length && text[index + 1] in ".!?…") continue
            var end = index + 1
            while (end < text.length && text[end] in closers) end++
            if (end < text.length && !text[end].isWhitespace()) {
                // Questions and exclamations still end without a following space.
                // A dot inside a number, URL or acronym is not a sentence boundary.
                if (mark == '.' && (!text[end].isUpperCase() || acronymDot(text, index))) continue
                if (!text[end].isLetterOrDigit() && text[end] !in openers) continue
            }
            val next = skipSpace(text, end)
            if (next >= text.length) continue
            if (mark == '.' && abbreviation(text, index)) continue
            // Ellipses inside a thought need not end it.
            if ((mark == '…' || index > 0 && text[index - 1] == '.') && !text[next].isUpperCase()) continue
            cuts += next
        }
        val ends = cuts.toList() + text.length
        var start = 0
        return ends.mapNotNull { end ->
            val range = start until end
            start = end
            range.takeUnless { it.isEmpty() }
        }
    }

    fun crosses(ranges: List<IntRange>, start: Int, end: Int): Boolean =
        ranges.dropLast(1).any { start < it.last + 1 && it.last + 1 < end }

    private fun skipSpace(text: String, from: Int): Int {
        var at = from
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }

    private fun acronymDot(text: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && text[start - 1].isLetter()) start--
        var end = dot + 1
        while (end < text.length && text[end].isLetter()) end++
        return start < dot && end > dot + 1 && end < text.length && text[end] == '.' &&
            text.substring(start, dot).all { it.isUpperCase() } &&
            text.substring(dot + 1, end).all { it.isUpperCase() }
    }

    private fun abbreviation(text: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && text[start - 1].isLetter()) start--
        return text.substring(start, dot).lowercase() in abbreviations
    }

    private val abbreviations = setOf("dr", "dra", "sr", "sra", "srta", "mr", "mrs", "ms", "prof", "núm", "num", "etc")
    private val closers = setOf('"', '\'', '”', '’', '»', ')', ']', '}')
    private val openers = setOf('"', '\'', '“', '‘', '«', '¿', '¡')
    private val paragraphBreak = Regex("(?:\\r\\n|\\r(?!\\n)|(?<!\\r)\\n|[\\u0085\\u2028])[\\t \\u00a0]*(?:\\r\\n|\\r(?!\\n)|(?<!\\r)\\n|[\\u0085\\u2028])|\\u2029")
}
