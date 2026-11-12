package com.evanaronson.linguize.ui.card

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.editOrder

/**
 * Lab, Review flow: the order the card walks its changes in. Screen order: the sections
 * in the order the card shows them ([kinds], fixes before rewordings), and within each
 * section reading order, the same text order the edit engine renders in. Deletions and
 * insertions sit at their own place, like any other change. Fixes therefore come before
 * the rewordings that overlap them, which is how rewordings are written: on top of the
 * corrected text, so accepting the fix first never loses the rewording.
 */
internal fun Revision.reviewOrder(kinds: List<EditKind>): List<Edit> =
    kinds.distinct().flatMap { kind -> edits(kind).sortedWith(editOrder) }

/** The changes still on offer, in [reviewOrder]. */
internal fun Revision.reviewQueue(kinds: List<EditKind>): List<Edit> {
    val open = kinds.distinct().flatMap { remaining(it) }.mapTo(HashSet()) { it.id }
    return reviewOrder(kinds).filter { it.id in open }
}

/**
 * Where the writer is in the queue: the selected change (its explanation is open) and the
 * changes they skipped. Pure: each step returns a new focus.
 */
internal data class ReviewFocus(val selected: Int?, val skipped: Set<Int> = emptySet()) {
    /** Nothing left to walk to: every open change was accepted or skipped. */
    fun isDone(revision: Revision, kinds: List<EditKind>) =
        selected == null && revision.reviewQueue(kinds).all { it.id in skipped }

    /** A tap on a change, or on the open one again (null) to close it. */
    fun select(id: Int?) = copy(selected = id)

    /** Leaves the selected change open, unaccepted, and moves to the next one. */
    fun skip(revision: Revision, kinds: List<EditKind>): ReviewFocus {
        val id = selected ?: return this
        val skipped = skipped + id
        return ReviewFocus(next(id, revision, kinds, skipped), skipped)
    }

    /**
     * Follows the revision from [old] to [new]. An Undo selects the change it brought back.
     * Otherwise, when the selected change was accepted (or retired by an accepted
     * rewording), the next open change after it is selected, wrapping to the start once.
     * A change accepted from elsewhere (a screen-reader action) leaves the selection alone.
     */
    fun follow(old: Revision, new: Revision, kinds: List<EditKind>): ReviewFocus {
        if (old.original != new.original || old.edits != new.edits) return start(new, kinds)
        if (new.steps.size < old.steps.size) {
            val undone = old.steps.drop(new.steps.size).flatten().toSet()
            val back = new.reviewQueue(kinds).firstOrNull { it.id in undone }
                ?: return this
            return ReviewFocus(back.id, skipped - undone)
        }
        val id = selected ?: return this
        if (new.reviewQueue(kinds).any { it.id == id }) return this
        return copy(selected = next(id, new, kinds, skipped))
    }

    /** "Change 2 of 5": the selected change's place among all of this check's changes. */
    fun position(revision: Revision, kinds: List<EditKind>): Pair<Int, Int>? {
        val id = selected ?: return null
        val all = revision.reviewOrder(kinds).filterNot { revision.isRetired(it.id) }
        val index = all.indexOfFirst { it.id == id }
        return if (index < 0) null else index + 1 to all.size
    }

    companion object {
        /** A fresh result opens on its first change. */
        fun start(revision: Revision, kinds: List<EditKind>) =
            ReviewFocus(revision.reviewQueue(kinds).firstOrNull()?.id)

        private fun next(after: Int, revision: Revision, kinds: List<EditKind>, skipped: Set<Int>): Int? {
            val order = revision.reviewOrder(kinds).map { it.id }
            val from = order.indexOf(after)
            val candidates = revision.reviewQueue(kinds).filter { it.id != after && it.id !in skipped }
            return (candidates.firstOrNull { order.indexOf(it.id) > from } ?: candidates.firstOrNull())?.id
        }
    }
}

/**
 * The card's one selection across its sections, kept while the same check is shown.
 * [focus] follows the revision as it is read, so the next change is selected in the same
 * frame as the accept that moved past the last one.
 */
@Stable
internal class ReviewFlow(private val kinds: List<EditKind>, revision: Revision) {
    private var seen = revision
    private var current = ReviewFocus.start(revision, kinds)
    /** Bumped by the writer's own moves (tap, skip) so readers recompose. */
    private var moves by mutableIntStateOf(0)

    fun focus(revision: Revision): ReviewFocus {
        moves // read, so a tap or skip recomposes the readers
        if (revision != seen) {
            current = current.follow(seen, revision, kinds)
            seen = revision
        }
        return current
    }

    fun select(id: Int?) {
        current = current.select(id)
        moves++
    }

    fun skip() {
        current = current.skip(seen, kinds)
        moves++
    }
}

/** What one section's text needs of the shared selection. */
class ReviewSelection(
    /** The open change, in any section; null when none is. */
    val selected: Int?,
    val onSelect: (Int?) -> Unit,
    /** Null where nothing can be accepted. */
    val onSkip: (() -> Unit)?,
    /** "Change 2 of 5" for the open change, for screen readers. */
    val position: String?,
    /** Lab, Type to accept: the open change is accepted by typing it, not with a button. */
    val typeToAccept: Boolean = false,
)
