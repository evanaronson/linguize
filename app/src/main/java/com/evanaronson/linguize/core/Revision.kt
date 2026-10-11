package com.evanaronson.linguize.core

/**
 * The writer's text plus every suggested edit, and which ones they've accepted.
 * Immutable: each action returns a new Revision.
 *
 * Rules, where a rewording and a fix "touch" as defined by [Edit.touches]:
 * 1. Edits of the same kind never touch each other.
 * 2. Rewordings are written on top of the corrected text, so a rewording already
 *    includes every fix it touches. The rewording wins.
 * 3. Accepting a rewording retires the fixes it touches. A retired fix is no longer
 *    offered: not shown, not counted, not applied by Replace all.
 * 4. Accepting a fix leaves the rewordings that touch it on offer. Accepting one of
 *    them later replaces the fix along with the rest of the phrase.
 * 5. Fixes a rewording doesn't touch stay on offer, because the rewording assumes them.
 * 6. Replace all accepts a kind's remaining edits as one action.
 * 7. Undo reverts the last action, bringing back what it accepted and what that retired.
 * The working text is the original with the accepted edits applied, leaving out
 * accepted fixes that an accepted rewording replaced.
 */
data class Revision(
    val original: String,
    val edits: List<Edit>,
    /** Accepted edit ids, one entry per action, so Undo reverts a whole "Replace all". */
    val steps: List<List<Int>> = emptyList(),
) {
    private val accepted: Set<Int> = steps.flatten().toSet()

    /** Accepted changes that show in the text: a fix a rewording replaced doesn't count. */
    val acceptedCount get() = shown(acceptedEdits).size
    val canUndo get() = steps.isNotEmpty()

    fun edits(kind: EditKind) = edits.filter { it.kind == kind }

    /** Edits still on offer: neither accepted nor retired. */
    fun remaining(kind: EditKind) = edits(kind).filter { it.id !in accepted && !retired(it) }

    fun accept(id: Int): Revision {
        val edit = edits.firstOrNull { it.id == id } ?: return this
        return if (id in accepted || retired(edit)) this else copy(steps = steps + listOf(listOf(id)))
    }

    fun acceptAll(kind: EditKind): Revision {
        val ids = remaining(kind).map { it.id }
        return if (ids.isEmpty()) this else copy(steps = steps + listOf(ids))
    }

    fun undo() = copy(steps = steps.dropLast(1))

    /** The edits accepted so far. */
    val acceptedEdits: List<Edit> get() = edits.filter { it.id in accepted }

    /**
     * Accepts, as one action, the edits identical to [previous] (same kind, span and
     * replacement): after a re-check, what the writer had accepted stays accepted
     * wherever the new suggestions didn't change. Each of [previous] accepts one edit at
     * most, so of several identical suggestions (three inserted "."s) only as many stay
     * accepted as the writer had accepted.
     */
    fun acceptMatching(previous: List<Edit>): Revision {
        val unmatched = previous.toMutableList()
        val matched = mutableSetOf<Int>()
        // Identical suggestions pair up by their place in the corrected text first.
        for (byOrder in listOf(true, false)) {
            for (edit in edits) {
                if (edit.id in matched) continue
                val match = unmatched.indexOfFirst {
                    it.kind == edit.kind && it.start == edit.start && it.end == edit.end && it.replacement == edit.replacement &&
                        (!byOrder || it.order == edit.order)
                }
                if (match < 0) continue
                unmatched.removeAt(match)
                matched += edit.id
            }
        }
        val ids = edits.filter { it.id in matched }.map { it.id }
        return if (ids.isEmpty()) this else copy(steps = steps + listOf(ids))
    }

    /** The text with accepted edits applied: what goes back to the app. */
    val workingText: String get() = render(original, shown(acceptedEdits)).text

    /** The working text with all of [kind]'s remaining edits applied too, and where they sit. */
    fun preview(kind: EditKind): Rendered = render(original, shown(acceptedEdits + remaining(kind)))

    /** Whether edit [id] is accepted and shows in the working text: not a fix an accepted rewording replaced. */
    fun isApplied(id: Int) = edits.any { it.id == id && id in accepted && !retired(it) }

    /** Whether edit [id] is a fix retired by an accepted rewording that touches it. */
    fun isRetired(id: Int) = edits.any { it.id == id && retired(it) }

    /** A fix touched by an accepted rewording. */
    private fun retired(edit: Edit) =
        edit.kind == EditKind.Fix && edits.any { it.id in accepted && it.kind == EditKind.Natural && it.touches(edit) }
}

/** [edits] without the fixes that a rewording among them replaces: what of them shows in the text. */
internal fun shown(edits: List<Edit>) = edits.filterNot { fix ->
    fix.kind == EditKind.Fix && edits.any { it.kind == EditKind.Natural && it.touches(fix) }
}
