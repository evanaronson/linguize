package com.evanaronson.linguize.core.writingpad

import com.evanaronson.linguize.core.Alignment

/**
 * One changed stretch between two versions of a text: [oldStart] until [oldEnd] in the old
 * text became [newStart] until [newEnd] in the new one. Either side can be empty.
 */
data class Hunk(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int) {
    /** How much longer the text got here. */
    val delta get() = (newEnd - newStart) - (oldEnd - oldStart)
    val isInsertion get() = oldStart == oldEnd
}

/**
 * Where two versions of the writer's text differ. Typing changes one place, which the shared
 * start and end find exactly. A larger change (a paste over several sentences) is aligned with
 * the edit engine's [Alignment], so the unchanged words inside it still count as unchanged.
 */
object TextDiff {
    fun hunks(old: String, new: String): List<Hunk> {
        if (old == new) return emptyList()
        var prefix = 0
        val shorter = minOf(old.length, new.length)
        while (prefix < shorter && old[prefix] == new[prefix]) prefix++
        // Never split a surrogate pair or put a combining mark on the far side of its letter.
        while (prefix > 0 && (old.getOrNull(prefix)?.let(::isContinuation) == true ||
                new.getOrNull(prefix)?.let(::isContinuation) == true)) prefix--
        var suffix = 0
        while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        while (suffix > 0 && (isContinuation(old[old.length - suffix]) || isContinuation(new[new.length - suffix]))) suffix--
        val oldEnd = old.length - suffix
        val newEnd = new.length - suffix
        val whole = Hunk(prefix, oldEnd, prefix, newEnd)
        if (prefix == oldEnd || prefix == newEnd || (oldEnd - prefix) + (newEnd - prefix) > ALIGN_LIMIT) return listOf(whole)
        val ops = try {
            Alignment.align(old.substring(prefix, oldEnd), new.substring(prefix, newEnd))
        } catch (_: IllegalArgumentException) {
            return listOf(whole)
        }
        val hunks = mutableListOf<Hunk>()
        var oldAt = 0
        var newAt = 0
        var pending: Hunk? = null
        for (op in ops) {
            val same = op.a != null && op.b != null && op.a.text == op.b.text
            val oldNext = op.a?.end ?: oldAt
            val newNext = op.b?.end ?: newAt
            if (same) {
                pending?.let { hunks += it }
                pending = null
            } else {
                pending = pending?.copy(oldEnd = oldNext + prefix, newEnd = newNext + prefix)
                    ?: Hunk(oldAt + prefix, oldNext + prefix, newAt + prefix, newNext + prefix)
            }
            oldAt = oldNext
            newAt = newNext
        }
        pending?.let { hunks += it }
        // The walk must account for every character, or the map would be wrong.
        if (oldAt != oldEnd - prefix || newAt != newEnd - prefix) return listOf(whole)
        return hunks.ifEmpty { listOf(whole) }
    }

    /** A character that belongs to the one before it. */
    private fun isContinuation(c: Char) =
        Character.isLowSurrogate(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(c) == Character.ENCLOSING_MARK.toInt() || c == '\u200D' || c in '\uFE00'..'\uFE0F'

    /**
     * Past this many changed characters, one hunk is cheap and good enough: it runs on every
     * keystroke on the main thread, and the next check offers what it drops again.
     */
    private const val ALIGN_LIMIT = 1200
}
