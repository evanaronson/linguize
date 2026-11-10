package com.evanaronson.linguize.ui.card

/**
 * Lab, Review flow: the text as the card draws it, with a mark at the place of each change
 * that has nothing visible to underline. A deleted word stays in the text, struck through;
 * a line break shows as ¶ (struck through when it goes); other spacing shows as a caret ‸.
 * The mark is underlined, tinted and tapped like any other change, so deletions take their
 * turn in reading order instead of sitting apart.
 *
 * Only the drawing changes: the replacement, its range in [text], Copy and the
 * explanation all keep using the real text.
 */
internal data class MarkedText(
    /** What is drawn. Equals the real text when no change needed a mark. */
    val text: String,
    /** Each change's range in [text]: its replacement, or its mark. */
    val ranges: Map<Int, IntRange>,
    /** Marks that stand for removed text, drawn struck through. */
    val struck: List<IntRange>,
    /** Whether any mark was added. */
    val hasMarks: Boolean,
)

internal const val CARET_MARK = "‸"
internal const val LINE_BREAK_MARK = "¶"

/** Adds a mark for each of [changes] that [needs one][needsPanel]. */
internal fun markChanges(text: String, changes: List<Highlight>): MarkedText {
    val marks = changes.filter { it.needsPanel(text) }.sortedBy { it.range.first }
    if (marks.isEmpty()) return MarkedText(text, changes.associate { it.id to it.range }, emptyList(), hasMarks = false)

    val out = StringBuilder()
    val struck = mutableListOf<IntRange>()
    val markRanges = mutableMapOf<Int, IntRange>()
    /** Where each real position moved: inserted marks push later text along. */
    val inserts = mutableListOf<Pair<Int, Int>>() // real position, characters inserted before it
    var pos = 0
    for (change in marks) {
        val at = change.range.first.coerceIn(pos, text.length)
        out.append(text, pos, at)
        pos = at
        val (mark, isStruck) = mark(change, text)
        val before = out.lastOrNull()
        val after = text.getOrNull(at)
        // A struck word gets a space where it would otherwise run into a neighbouring word.
        val word = mark != CARET_MARK && mark != LINE_BREAK_MARK
        val padBefore = word && before != null && !before.isWhitespace() && mark.first().isLetterOrDigit()
        val padAfter = word && after != null && after.isLetterOrDigit()
        val startLength = out.length
        if (padBefore) out.append(' ')
        val markStart = out.length
        out.append(mark)
        val markRange = markStart until out.length
        if (padAfter) out.append(' ')
        markRanges[change.id] = markRange
        if (isStruck) struck += markRange
        inserts += at to (out.length - startLength)
    }
    out.append(text, pos, text.length)

    fun shift(position: Int) = position + inserts.filter { (at, _) -> at <= position }.sumOf { it.second }
    val ranges = changes.associate { change ->
        change.id to (markRanges[change.id] ?: (shift(change.range.first)..shift(change.range.last)))
    }
    return MarkedText(out.toString(), ranges, struck, hasMarks = true)
}

/** The mark for one invisible change, and whether it stands for removed text. */
private fun mark(change: Highlight, text: String): Pair<String, Boolean> {
    val replacement = text.substring(change.range)
    val removed = change.from.trim()
    return when {
        removed.isNotEmpty() -> removed to true
        change.from.any { it.isLineBreakChar() } -> LINE_BREAK_MARK to true
        replacement.any { it.isLineBreakChar() } -> LINE_BREAK_MARK to false
        else -> CARET_MARK to false
    }
}

private fun Char.isLineBreakChar() = this in "\r\n\u0085  "
