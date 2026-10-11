package com.evanaronson.linguize.core

import com.evanaronson.linguize.codec.Tokens

/** Which section of the card an edit belongs to. */
enum class EditKind(
    /** What history keeps. */
    val token: String,
) {
    Fix("fix"),
    Natural("natural"),
    ;

    companion object {
        val tokens = Tokens(entries, EditKind::token)
    }
}

/**
 * One suggested change, anchored to a span of the writer's original text, so
 * edits can be accepted in any order without disturbing each other.
 */
data class Edit(
    val id: Int,
    val kind: EditKind,
    /** Start of the replaced span in the original text. */
    val start: Int,
    /** End of the span (exclusive); equal to [start] when something is only inserted. */
    val end: Int,
    /** The original text being replaced; empty for an insertion. */
    val from: String,
    val replacement: String,
    val why: String?,
    /**
     * For a rewording: the fixes its replacement already includes, since rewordings
     * are written on top of the corrected text. Covers fixes that only sit at its
     * edge, like a comma inserted right where it starts.
     */
    val includes: Set<Int> = emptySet(),
    /**
     * Where the replacement sits in the corrected text, which every edit is placed
     * against, so insertions at one place in the original render in the order their
     * texts have there, whatever their kind. Twice the corrected-text position, plus
     * one unless the edit replaces nothing there: a rewording inserted where a fix's
     * text starts goes before it.
     */
    val order: Int = 0,
) {
    val isInsertion get() = start == end

    /** Whether two edits touch: they [overlap][overlaps], or one includes the other. */
    fun touches(other: Edit) = overlaps(other) || other.id in includes || id in other.includes

    /**
     * Whether two spans overlap: they share a character, or an insertion lands strictly
     * inside the other's span. Edits that only sit next to each other don't overlap, nor
     * do two insertions at the same place, which render one after the other.
     */
    fun overlaps(other: Edit): Boolean = when {
        !isInsertion && !other.isInsertion -> start < other.end && other.start < end
        isInsertion && !other.isInsertion -> other.start < start && start < other.end
        !isInsertion && other.isInsertion -> start < other.start && other.start < end
        else -> false
    }
}

/** Text with edits applied, and the range each edit's replacement occupies in it. */
data class Rendered(val text: String, val ranges: Map<Int, IntRange>)

/**
 * Text order. At the same place an insertion goes before a replacement, and insertions
 * keep the order they have in the corrected text, whatever their kind.
 */
internal val editOrder = compareBy<Edit>({ it.start }, { if (it.isInsertion) 0 else 1 }, { it.order }, { it.id })

/** Applies non-overlapping [edits] to [original]. */
internal fun render(original: String, edits: List<Edit>): Rendered {
    val out = StringBuilder()
    val ranges = mutableMapOf<Int, IntRange>()
    var pos = 0
    for (edit in edits.sortedWith(editOrder)) {
        if (edit.start < pos) continue
        out.append(original, pos, edit.start)
        val start = out.length
        out.append(edit.replacement)
        if (edit.replacement.isNotEmpty()) ranges[edit.id] = start until out.length
        pos = edit.end
    }
    out.append(original, pos, original.length)
    return Rendered(out.toString(), ranges)
}
