package com.evanaronson.linguize.core.writingpad

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.core.SuggestionAssessment

/**
 * A change offered on text the writer is still typing: an [com.evanaronson.linguize.core.Edit]
 * moved onto the live text. [start] until [end] holds exactly [from] in that text.
 * [key] stays the same while the suggestion moves; [linked] are suggestions that must not be
 * accepted together with it (a rewording and the fixes its replacement already includes).
 */
data class LiveSuggestion(
    val key: Long,
    val kind: EditKind,
    val start: Int,
    val end: Int,
    val from: String,
    val replacement: String,
    val why: String?,
    val assessment: SuggestionAssessment? = null,
    val linked: Set<Long> = emptySet(),
) {
    val isInsertion get() = start == end

    /** Whether a cursor at [offset] sits on this suggestion, including at either edge. */
    fun covers(offset: Int) = offset in start..end
}

/** Moves suggestions from one version of the text to the next, dropping the ones the change made unsafe. */
object LiveSuggestions {
    /**
     * The suggestions of a finished check of [checked], as live suggestions on the same text.
     * Keys come from [nextKey]. Edits whose span no longer holds their text are left out.
     */
    fun of(checked: String, result: CheckResult.Reviewed, nextKey: () -> Long): List<LiveSuggestion> {
        val edits = result.revision.edits.filter { it.kind in result.kinds }
            .filter { it.start in 0..it.end && it.end <= checked.length && checked.substring(it.start, it.end) == it.from }
            .filter { it.from != it.replacement }
        val keys = edits.associate { it.id to nextKey() }
        return edits.map { edit ->
            val linked = edit.includes.mapNotNull(keys::get) + edits.filter { edit.id in it.includes }.map { keys.getValue(it.id) }
            LiveSuggestion(
                key = keys.getValue(edit.id),
                kind = edit.kind,
                start = edit.start,
                end = edit.end,
                from = edit.from,
                replacement = edit.replacement,
                why = edit.why?.takeIf { it.isNotBlank() },
                assessment = edit.assessment,
                linked = linked.toSet(),
            )
        }
    }

    /**
     * [items] on [old], moved onto [new]. A suggestion is dropped when the change overlaps it,
     * lands right against it inside a word, or no longer leaves its text in place. With
     * [sentenceScope], one is also dropped when its sentence was edited, since a fix can depend
     * on the words around it (agreement, word order); the next check offers it again if it still holds.
     */
    fun remap(old: String, new: String, items: List<LiveSuggestion>, sentenceScope: Boolean): List<LiveSuggestion> {
        if (old == new || items.isEmpty()) return items
        return through(old, new, TextDiff.hunks(old, new), items, sentenceScope)
    }

    internal fun through(
        old: String,
        new: String,
        hunks: List<Hunk>,
        items: List<LiveSuggestion>,
        sentenceScope: Boolean,
    ): List<LiveSuggestion> {
        if (hunks.isEmpty()) return items
        val sentences = if (sentenceScope) Sentences.ranges(old) else emptyList()
        return items.mapNotNull { item ->
            if (item.start < 0 || item.end > old.length || old.substring(item.start, item.end) != item.from) return@mapNotNull null
            if (hunks.any { invalidates(it, item, old, new) }) return@mapNotNull null
            if (sentenceScope) {
                val (first, last) = sentenceOf(sentences, item, old.length)
                if (hunks.any { edits(it, first, last, new) }) return@mapNotNull null
            }
            val start = item.start + hunks.filter { it.oldEnd <= item.start }.sumOf { it.delta }
            val end = if (item.isInsertion) start else item.end + hunks.filter { it.oldEnd < item.end }.sumOf { it.delta }
            if (start < 0 || end > new.length || start > end || new.substring(start, end) != item.from) return@mapNotNull null
            item.copy(start = start, end = end)
        }
    }

    /**
     * What accepting [accepted] leaves of [others] once [newText] holds its replacement.
     * A rewording that contains an accepted fix stays on offer, now replacing the corrected
     * words (its replacement already includes the fix); other linked suggestions go.
     */
    internal fun afterAccepting(
        oldText: String,
        newText: String,
        accepted: LiveSuggestion,
        others: List<LiveSuggestion>,
    ): List<LiveSuggestion> {
        val delta = accepted.replacement.length - (accepted.end - accepted.start)
        return others.mapNotNull { other ->
            val contains = other.start <= accepted.start && accepted.end <= other.end && !other.isInsertion
            when {
                accepted.kind == EditKind.Fix && other.kind == EditKind.Natural && contains -> {
                    val end = other.end + delta
                    val from = newText.substring(other.start, end)
                    if (from == other.replacement) null else other.copy(end = end, from = from, linked = other.linked - accepted.key)
                }
                other.key in accepted.linked || accepted.key in other.linked -> null
                else -> shifted(newText, accepted, other, delta)
            }
        }
    }

    /**
     * [other] after [accepted] was put in, when they came from the same check: such edits
     * only sit next to each other, so a neighbour stays valid. At one place an insertion goes
     * before a replacement, as the edit engine renders them.
     */
    private fun shifted(newText: String, accepted: LiveSuggestion, other: LiveSuggestion, delta: Int): LiveSuggestion? {
        val overlaps = when {
            !accepted.isInsertion && !other.isInsertion -> accepted.start < other.end && other.start < accepted.end
            accepted.isInsertion && !other.isInsertion -> other.start < accepted.start && accepted.start < other.end
            !accepted.isInsertion && other.isInsertion -> accepted.start < other.start && other.start < accepted.end
            else -> false
        }
        if (overlaps) return null
        val moved = when {
            other.end <= accepted.start -> other
            other.start >= accepted.end -> other.copy(start = other.start + delta, end = other.end + delta)
            else -> return null
        }
        return moved.takeIf { it.end <= newText.length && newText.substring(it.start, it.end) == it.from }
    }

    /** Whether [hunk] overlaps [item], or joins a word with it at either edge. */
    private fun invalidates(hunk: Hunk, item: LiveSuggestion, old: String, new: String): Boolean {
        val a = item.start
        val b = item.end
        if (item.isInsertion) return hunk.oldStart <= a && a <= hunk.oldEnd
        val overlaps = if (hunk.isInsertion) a < hunk.oldStart && hunk.oldStart < b
            else hunk.oldStart < b && a < hunk.oldEnd
        if (overlaps) return true
        if (hunk.oldEnd == a && isWord(old, a)) {
            val before = listOfNotNull(
                (hunk.newEnd - 1).takeIf { hunk.newEnd > hunk.newStart }?.let { isWord(new, it) },
                (hunk.oldEnd - 1).takeIf { hunk.oldEnd > hunk.oldStart }?.let { isWord(old, it) },
            )
            if (before.any { it }) return true
        }
        if (hunk.oldStart == b && isWord(old, b - 1)) {
            val after = listOfNotNull(
                hunk.newStart.takeIf { hunk.newEnd > hunk.newStart }?.let { isWord(new, it) },
                hunk.oldStart.takeIf { hunk.oldEnd > hunk.oldStart }?.let { isWord(old, it) },
            )
            if (after.any { it }) return true
        }
        return false
    }

    /**
     * Whether [hunk] changes something in the sentence text [first] until [last]. A whole
     * sentence put in front of it ("Hola! ") is a new sentence, not an edit of this one.
     */
    private fun edits(hunk: Hunk, first: Int, last: Int, new: String): Boolean {
        if (!hunk.isInsertion) return hunk.oldStart < last && first < hunk.oldEnd
        if (hunk.oldStart == first) {
            val inserted = new.substring(hunk.newStart, hunk.newEnd)
            if (inserted.last().isWhitespace() && inserted.trimEnd().lastOrNull()?.let { it in ".!?…" } == true) return false
        }
        return hunk.oldStart in first until last
    }

    /** The sentences [item] sits in, as one span of the text. */
    private fun sentenceOf(sentences: List<IntRange>, item: LiveSuggestion, length: Int): Pair<Int, Int> {
        if (sentences.isEmpty()) return 0 to length
        fun at(offset: Int) = sentences.firstOrNull { offset in it } ?: sentences.last()
        val first = at(item.start).first
        val last = at(maxOf(item.start, item.end - 1)).last + 1
        return first to last
    }

    private fun isWord(text: String, index: Int): Boolean {
        if (index !in text.indices) return false
        val c = text[index]
        return Character.isLetterOrDigit(c) || c == '\'' || c == '’' || c == '·' ||
            Character.isSurrogate(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
    }
}
