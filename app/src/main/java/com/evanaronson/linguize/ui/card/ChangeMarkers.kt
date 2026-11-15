package com.evanaronson.linguize.ui.card

/**
 * One change view: the text as the card draws it, with every change written in place. A
 * replaced word shows its old words struck through, then the new words; the change's mark
 * (underline, tint) runs under both. A deleted word stays in the text, struck through; a line
 * break shows as ¶ (struck through when it goes); other spacing shows as a caret ‸. Every
 * mark is underlined, tinted and tapped like any other change, so changes take their turn in
 * reading order. The card, Chat's corrected messages and History's saved checks all draw
 * changes this way (see docs/lab/review-flow.md, "One change view").
 *
 * A [skipped] change (the card's Skip) shows what will be sent for it: its original words,
 * plain, with no strike and no mark. One with nothing visible to keep (an insertion, spacing)
 * leaves a quiet ‸ or ¶ in its place, so it can still be tapped back. An accepted change isn't
 * passed in at all: its new words are simply part of the text.
 *
 * Only the drawing changes: the replacement, its range in [text], Copy and the
 * explanation all keep using the real text.
 */
internal data class MarkedText(
    /** What is drawn. Equals the real text when no change needed a mark. */
    val text: String,
    /** Each change's whole span in [text]: old words and new, or its mark. */
    val ranges: Map<Int, IntRange>,
    /** Old words and marks that stand for removed text, drawn struck through. */
    val struck: List<IntRange>,
    /** Each change's struck part, if it has one: what fades out when it is typed correctly. */
    val struckBy: Map<Int, IntRange> = emptyMap(),
    /** Each change's new words, if it shows any. */
    val added: Map<Int, IntRange> = emptyMap(),
    /** Changes drawn without a mark: skipped ones, showing their original words. */
    val quiet: Set<Int> = emptySet(),
    /** The ‸ and ¶ that keep a skipped invisible change's place, drawn in a quiet colour. */
    val quietMarks: List<IntRange> = emptyList(),
    /** Whether the drawn text differs from the real text. */
    val hasMarks: Boolean,
)

internal const val CARET_MARK = "‸"
internal const val LINE_BREAK_MARK = "¶"

/**
 * Writes every one of [changes] into [text] (see [MarkedText]); the [skipped] ones as their
 * original words.
 */
internal fun markChanges(text: String, changes: List<Highlight>, skipped: Set<Int> = emptySet()): MarkedText {
    val out = StringBuilder()
    val ranges = mutableMapOf<Int, IntRange>()
    val struck = mutableListOf<IntRange>()
    val struckBy = mutableMapOf<Int, IntRange>()
    val added = mutableMapOf<Int, IntRange>()
    val quiet = mutableSetOf<Int>()
    val quietMarks = mutableListOf<IntRange>()
    var pos = 0
    for (change in changes.sortedWith(compareBy({ it.range.first }, { it.range.last }))) {
        val at = change.range.first.coerceIn(pos, text.length)
        val end = (change.range.last + 1).coerceIn(at, text.length)
        out.append(text, pos, at)
        pos = at
        val replacement = text.substring(at, end)
        val removed = change.from.trim()
        val isSkipped = change.id in skipped
        if (isSkipped) quiet += change.id
        if (replacement.all { it.isWhitespace() || it.isLineBreakChar() }) {
            // A deletion or a spacing change: nothing visible to underline, so a mark stands in.
            if (isSkipped) {
                // What will be sent: the original, so the suggested spacing isn't drawn.
                pos = end
                if (removed.isNotEmpty()) {
                    ranges[change.id] = appendWord(out, removed, text.getOrNull(pos))
                } else {
                    val start = out.length
                    out.append(if (change.from.any { it.isLineBreakChar() }) LINE_BREAK_MARK else CARET_MARK)
                    ranges[change.id] = start until out.length
                    quietMarks += ranges.getValue(change.id)
                    out.append(change.from)
                }
                continue
            }
            val (mark, isStruck) = mark(change, replacement)
            val word = mark != CARET_MARK && mark != LINE_BREAK_MARK
            val markRange = if (word) appendWord(out, mark, text.getOrNull(at)) else {
                val start = out.length
                out.append(mark)
                start until out.length
            }
            ranges[change.id] = markRange
            if (isStruck) {
                struck += markRange
                struckBy[change.id] = markRange
            }
            continue
        }
        // A visible replacement or insertion: the old words struck through, then the new ones.
        // Spaces at its edges stay plain text, outside the mark.
        val lead = replacement.length - replacement.trimStart().length
        val core = replacement.trim()
        out.append(replacement, 0, lead)
        val start = out.length
        if (isSkipped) {
            if (removed.isNotEmpty()) out.append(removed) else {
                out.append(CARET_MARK)
                quietMarks += start until out.length
            }
        } else {
            if (removed.isNotEmpty()) {
                out.append(removed)
                val old = start until out.length
                struck += old
                struckBy[change.id] = old
                // Words keep a space between old and new; punctuation next to punctuation sits close.
                if (removed.last().isLetterOrDigit() || core.first().isLetterOrDigit()) out.append(' ')
            }
            val newStart = out.length
            out.append(core)
            added[change.id] = newStart until out.length
        }
        ranges[change.id] = start until out.length
        out.append(replacement, lead + core.length, replacement.length)
        pos = end
    }
    out.append(text, pos, text.length)
    val drawn = out.toString()
    return MarkedText(drawn, ranges, struck, struckBy, added, quiet, quietMarks, hasMarks = drawn != text)
}

/** Appends a struck or kept [word], with a space where it would otherwise run into a neighbouring word. */
private fun appendWord(out: StringBuilder, word: String, after: Char?): IntRange {
    val before = out.lastOrNull()
    if (before != null && !before.isWhitespace() && word.first().isLetterOrDigit()) out.append(' ')
    val start = out.length
    out.append(word)
    val range = start until out.length
    if (after != null && after.isLetterOrDigit()) out.append(' ')
    return range
}

/** The mark for one invisible change, and whether it stands for removed text. */
private fun mark(change: Highlight, replacement: String): Pair<String, Boolean> {
    val removed = change.from.trim()
    return when {
        removed.isNotEmpty() -> removed to true
        change.from.any { it.isLineBreakChar() } -> LINE_BREAK_MARK to true
        replacement.any { it.isLineBreakChar() } -> LINE_BREAK_MARK to false
        else -> CARET_MARK to false
    }
}

private fun Char.isLineBreakChar() = this in "\r\n\u0085  "
