package com.evanaronson.linguize.core

import com.evanaronson.linguize.core.Alignment.Op
import com.evanaronson.linguize.core.Alignment.Type
import java.text.BreakIterator

/**
 * Turns the model's full corrected and natural texts into edits of the
 * original, so positions never depend on the model reporting them; its change
 * lists only supply reasons.
 */
internal object Edits {
    /** One edit per changed word and one per punctuation mark. */
    fun fixes(original: String, corrected: String, reported: List<VerdictChange>): List<Edit> {
        val spans = tidy(original, spans(original, corrected, atomic = true))
        val fixes = toEdits(spans, reported, EditKind.Fix, firstId = 0)
        val placed = place(fixes).associateBy { it.fix.id }
        return fixes.map { it.copy(order = 2 * placed.getValue(it.id).baseStart + 1) }
    }

    /**
     * Phrase-level rewordings. The natural text is written on top of the
     * corrected one, so rewordings are found against the corrected text (where
     * the fixes cancel out), tidied there and then mapped back onto the original.
     * A rewording that covers a fixed word takes in that word's original form.
     */
    fun naturals(
        original: String,
        fixes: List<Edit>,
        natural: String,
        reported: List<VerdictChange>,
        firstId: Int = 0,
    ): List<Edit> {
        val base = render(original, fixes).text
        val placed = place(fixes)
        // Tidied in the corrected text, so moving an insertion past a space never jumps
        // over a fix's text, or into it.
        val inBase = tidy(base, located(base, natural, reported) ?: spans(base, natural, atomic = false)) { at ->
            placed.any { it.baseStart <= at && at < it.baseEnd }
        }
        val spans = mergeWithinFixes(inBase, base, placed).map { toOriginal(it, base, original, placed) }
            // Changes that only restate a fix, or undo one, don't change the original.
            .filterNot { Alignment.same(it.from, it.replacement) }
        return toEdits(spans, reported, EditKind.Natural, firstId)
    }

    private data class Span(
        val start: Int,
        val end: Int,
        val from: String,
        val replacement: String,
        val why: String? = null,
        /** Ids of the fixes this span's replacement includes. */
        val includes: Set<Int> = emptySet(),
        /** See [Edit.order]; only set for rewordings here, fixes get theirs once placed. */
        val order: Int = 0,
    )

    private fun toEdits(spans: List<Span>, reported: List<VerdictChange>, kind: EditKind, firstId: Int): List<Edit> {
        val reasons = reasons(spans, reported)
        return spans.mapIndexed { index, span ->
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, reasons[index], span.includes, span.order)
        }
    }

    /**
     * Each span's reason: its own, or the one of the model's changes that matches it best.
     * Closer kinds of match are settled for every span before looser ones, so a change
     * goes to the span it describes exactly ("si" → "sí") rather than to an earlier one
     * that merely contains its text ("asi" → "así").
     */
    private fun reasons(spans: List<Span>, reported: List<VerdictChange>): List<String?> {
        val unused = reported.toMutableList()
        val found = arrayOfNulls<VerdictChange>(spans.size)
        for (level in matches) {
            spans.forEachIndexed { index, span ->
                if (span.why != null || found[index] != null) return@forEachIndexed
                val to = span.replacement.trim()
                val from = span.from.trim()
                found[index] = unused.firstOrNull { level(from, to, it.from.trim(), it.to.trim()) }?.also { unused.remove(it) }
            }
        }
        return spans.mapIndexed { index, span -> span.why ?: found[index]?.let(::reason) }
    }

    /** Ways a span (its from and to) can match a reported change (its from and to), closest first. */
    private val matches: List<(String, String, String, String) -> Boolean> = listOf(
        { from, to, otherFrom, otherTo -> Alignment.same(otherTo, to) && Alignment.same(otherFrom, from) },
        { _, to, _, otherTo -> Alignment.same(otherTo, to) },
        { from, _, otherFrom, _ -> from.isNotEmpty() && Alignment.same(otherFrom, from) },
        { _, to, _, otherTo -> otherTo.isNotEmpty() && to.isNotEmpty() && (otherTo in to || to in otherTo) },
    )

    /** The model's rewordings found in [base], or null unless applying them produces [target]. */
    private fun located(base: String, target: String, reported: List<VerdictChange>): List<Span>? {
        if (reported.isEmpty()) return null
        val spans = mutableListOf<Span>()
        var searchFrom = 0
        for (change in reported) {
            val from = change.from.trim()
            val to = change.to.trim()
            // An empty side can't be placed or shown; let the alignment work it out instead.
            if (from.isEmpty() || to.isEmpty()) return null
            if (Alignment.same(from, to)) continue
            val at = find(base, from, searchFrom) ?: find(base, from, 0) ?: return null
            if (spans.any { at < it.end && it.start < at + from.length }) return null
            spans += Span(at, at + from.length, from, to, reason(change))
            searchFrom = at + from.length
        }
        spans.sortBy { it.start }
        val applied = StringBuilder()
        var pos = 0
        for (span in spans) {
            applied.append(base, pos, span.start).append(span.replacement)
            pos = span.end
        }
        applied.append(base, pos, base.length)
        return spans.takeIf { Alignment.same(applied.toString().trim(), target.trim()) }
    }

    private fun reason(change: VerdictChange) = change.why.trim().ifEmpty { null }

    // --- Mapping corrected-text positions back to the original -------------

    /** A fix and where its replacement sits in the corrected text. */
    private data class Placed(val fix: Edit, val baseStart: Int, val baseEnd: Int)

    private fun place(fixes: List<Edit>): List<Placed> {
        var delta = 0
        return fixes.sortedWith(editOrder).map { fix ->
            val start = fix.start + delta
            delta += fix.replacement.length - (fix.end - fix.start)
            Placed(fix, start, start + fix.replacement.length)
        }
    }

    /** [start] and [end] in the corrected text, moved out to the edges of any fix's text they fall strictly inside. */
    private fun widened(start: Int, end: Int, placed: List<Placed>): Pair<Int, Int> {
        var from = start
        var to = end
        for (p in placed) {
            if (p.baseStart < from && from < p.baseEnd) from = p.baseStart
            if (p.baseStart < to && to < p.baseEnd) to = p.baseEnd
        }
        return from to to
    }

    /**
     * Joins rewordings that would overlap once widened to take in whole fixes, like two
     * that end and start inside the same fix. Afterwards no two rewordings share a fix.
     */
    private fun mergeWithinFixes(spans: List<Span>, base: String, placed: List<Placed>): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            merged += if (last != null && widened(span.start, span.end, placed).first < widened(last.start, last.end, placed).second) {
                merged.removeAt(merged.lastIndex)
                Span(
                    start = last.start,
                    end = span.end,
                    from = base.substring(last.start, span.end),
                    replacement = last.replacement + base.substring(last.end, span.start) + span.replacement,
                    why = last.why ?: span.why,
                )
            } else {
                span
            }
        }
        return merged
    }

    /** [span] is in corrected-text positions; the result is in original positions. */
    private fun toOriginal(span: Span, base: String, original: String, placed: List<Placed>): Span {
        // A span edge inside a fix's replacement widens to take in the whole fix.
        val (start, end) = widened(span.start, span.end, placed)
        val prefix = base.substring(start, span.start)
        val suffix = base.substring(span.end, end)
        val from = originalPosition(start, placed)
        val to = originalPosition(end, placed)
        // Fixes whose corrected text lies inside the span are part of the rewording,
        // including insertions at its edges, which share no original characters with it.
        val includes = placed
            .filter { it.baseStart < it.baseEnd && start <= it.baseStart && it.baseEnd <= end }
            .map { it.fix.id }
            .toSet()
        val order = 2 * start + if (start < end) 1 else 0
        return Span(from, to, original.substring(from, to), prefix + span.replacement + suffix, span.why, includes, order)
    }

    /** Maps a corrected-text position that isn't inside any fix to the original. */
    private fun originalPosition(position: Int, placed: List<Placed>): Int {
        var delta = 0
        for (p in placed) {
            if (position <= p.baseStart) return position - (p.baseStart - p.fix.start)
            delta = p.baseEnd - p.fix.end
        }
        return position - delta
    }

    // --- Grouping the alignment into spans ---------------------------------

    /** Spans of [base] that change to reach [target]: atomic for fixes, whole phrases for rewordings. */
    private fun spans(base: String, target: String, atomic: Boolean): List<Span> {
        val ops = Alignment.align(base, target)
        if (ops.isEmpty()) return emptyList()

        // Position in the base text before each op, for placing insertions.
        val before = IntArray(ops.size)
        var pos = 0
        ops.forEachIndexed { k, op ->
            before[k] = pos
            op.a?.let { pos = it.end }
        }

        return group(ops, atomic).map { group ->
            val removed = (group[0]..group[1]).mapNotNull { ops[it].a }
            val start = removed.firstOrNull()?.start ?: before[group[0]]
            val end = removed.lastOrNull()?.end ?: start
            Span(start, end, base.substring(start, end), replacement(ops, group))
        }
    }

    /** Op-index ranges, each one edit. */
    private fun group(ops: List<Op>, atomic: Boolean): List<IntArray> {
        // Consecutive changes form a group; for fixes, every punctuation mark stands alone.
        val groups = mutableListOf<IntArray>()
        var current: IntArray? = null
        for ((k, op) in ops.withIndex()) {
            val open = current
            if (op.isMatch) {
                // In a rewording, a single kept space between two changes doesn't end the phrase.
                val bridges = !atomic && open != null && op.token.type == Type.Space &&
                    k + 1 < ops.size && !ops[k + 1].isMatch
                if (bridges) open!![1] = k else current = null
            } else if (atomic && op.isPunct) {
                groups += intArrayOf(k, k)
                current = null
            } else if (open == null) {
                current = intArrayOf(k, k).also { groups += it }
            } else {
                open[1] = k
            }
        }

        // A group that only changes spacing joins the group before it.
        val joined = mutableListOf<IntArray>()
        for (group in groups) {
            val onlySpace = (group[0]..group[1]).all { ops[it].token.type == Type.Space }
            val previous = joined.lastOrNull()
            if (onlySpace && previous != null && previous[1] == group[0] - 1) previous[1] = group[1] else joined += group
        }

        // A deletion that leaves at most a space has nothing to show, so it joins the word change
        // next to it ("y neo" → "Neo"), or else grows to include the nearest unchanged word.
        // Changes to spacing alone are left as they are; tidy() drops them.
        fun isKeptSpace(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Space
        fun isKeptWord(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Word
        fun wordGroupAt(k: Int) = joined.firstOrNull { k in it[0]..it[1] }
            ?.takeIf { group -> (group[0]..group[1]).none { ops[it].isPunct } }
        for (group in joined) {
            val onlySpacing = (group[0]..group[1]).all { ops[it].token.type == Type.Space }
            if (onlySpacing || replacement(ops, group).isNotBlank()) continue
            var after = group[1] + 1
            while (after < ops.size && isKeptSpace(after)) after++
            var before = group[0] - 1
            while (before >= 0 && isKeptSpace(before)) before--
            val next = if (after < ops.size && !ops[after].isMatch) wordGroupAt(after) else null
            val previous = if (before >= 0 && !ops[before].isMatch) wordGroupAt(before) else null
            when {
                next != null -> group[1] = next[1]
                previous != null -> group[0] = previous[0]
                else -> {
                    val kept = (group[1] + 1 until ops.size).firstOrNull(::isKeptWord)
                    if (kept != null) {
                        group[1] = kept
                    } else {
                        (group[0] - 1 downTo 0).firstOrNull(::isKeptWord)?.let { group[0] = it }
                    }
                }
            }
        }

        val merged = mutableListOf<IntArray>()
        for (group in joined.sortedBy { it[0] }) {
            val previous = merged.lastOrNull()
            if (previous != null && group[0] <= previous[1]) previous[1] = maxOf(previous[1], group[1]) else merged += group
        }
        return merged
    }

    private fun replacement(ops: List<Op>, group: IntArray) =
        (group[0]..group[1]).mapNotNull { ops[it].b?.text }.joinToString("")

    /**
     * Keeps spaces out of highlights ("␣estas amb" → "␣portes" becomes "estas amb" →
     * "portes") and drops changes to spacing alone, which would have nothing to show.
     * [fixed] tells which characters of [text] a fix put there.
     */
    private fun tidy(text: String, spans: List<Span>, fixed: (Int) -> Boolean = { false }): List<Span> {
        val trimmed = spans.map(::trimSpaces)
            .filterNot { it.replacement.isEmpty() && it.from.isBlank() || Alignment.same(it.from, it.replacement) }
        val characters by lazy { BreakIterator.getCharacterInstance().apply { setText(text) } }
        return trimmed.map { span ->
            // An inserted "␣al" before a space is the same as "al␣" after it, unless something
            // else is inserted at the same place, which the move would jump over, or the space
            // belongs to another change ([fixed], or another span), which it would land inside.
            // The space moves whole, so a line break ("\r\n") is never split.
            val replacement = span.replacement
            val space = firstSpace(replacement)
            val length = space.length
            val alone = trimmed.none { it !== span && it.start < span.start + length && span.start < maxOf(it.end, it.start + 1) }
            if (alone && span.start == span.end && replacement.length > length && length > 0 &&
                firstSpace(text, span.start) == space && (span.start until span.start + length).none(fixed) &&
                characters.isBoundary(span.start + length)
            ) {
                span.copy(start = span.start + length, end = span.end + length, replacement = replacement.drop(length) + space)
            } else {
                span
            }
        }
    }

    private fun trimSpaces(span: Span): Span {
        var (start, end, from, replacement) = span
        while (true) {
            val space = firstSpace(from)
            if (space.isEmpty() || firstSpace(replacement) != space) break
            from = from.drop(space.length)
            replacement = replacement.drop(space.length)
            start += space.length
        }
        while (true) {
            val space = lastSpace(from)
            if (space.isEmpty() || lastSpace(replacement) != space) break
            from = from.dropLast(space.length)
            replacement = replacement.dropLast(space.length)
            end -= space.length
        }
        return span.copy(start = start, end = end, from = from, replacement = replacement)
    }

    /** The space character [text] has at [at], taking "\r\n" as one; empty if something else is there. */
    private fun firstSpace(text: String, at: Int = 0) = when {
        text.startsWith("\r\n", at) -> "\r\n"
        text.getOrNull(at)?.isWhitespace() == true -> text.substring(at, at + 1)
        else -> ""
    }

    /** The space character [text] ends with, taking "\r\n" as one; empty if it ends with something else. */
    private fun lastSpace(text: String) = when {
        text.endsWith("\r\n") -> "\r\n"
        text.lastOrNull()?.isWhitespace() == true -> text.substring(text.length - 1)
        else -> ""
    }

    /**
     * Index of [target] in [text] at or after [from], preferring a match that isn't
     * inside a longer word, so "bien" doesn't land in "también". A match that would cut
     * a character in two (part of an emoji, or a letter without its accent mark) doesn't
     * count: an edit there would break the character.
     */
    private fun find(text: String, target: String, from: Int): Int? {
        val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
        var fallback: Int? = null
        var index = text.indexOf(target, from)
        while (index >= 0) {
            if (!characters.isBoundary(index) || !characters.isBoundary(index + target.length)) {
                index = text.indexOf(target, index + 1)
                continue
            }
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + target.length)
            val wholeWord = (before == null || !before.isLetter() || !target.first().isLetter()) &&
                (after == null || !after.isLetter() || !target.last().isLetter())
            if (wholeWord) return index
            if (fallback == null) fallback = index
            index = text.indexOf(target, index + 1)
        }
        return fallback
    }
}
