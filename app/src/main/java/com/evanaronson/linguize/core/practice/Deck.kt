package com.evanaronson.linguize.core.practice

import kotlinx.serialization.Serializable

/**
 * What practice keeps between runs: each item's [ReviewState] by suggestion id, and how
 * many new items were started on [newDay] (a local epoch day), so a long History doesn't
 * arrive as one wall of due items.
 */
@Serializable
data class PracticeProgress(
    val states: Map<String, ReviewState> = emptyMap(),
    val newDay: Long = -1,
    val newToday: Int = 0,
)

/** Which items are due, what one run holds, and how answers change [PracticeProgress]. */
object Deck {
    const val NEW_PER_DAY = 10
    const val SESSION_MAX = 10
    const val SESSION_MIN = 5

    /** New items that may still start on [today]. */
    fun newAllowance(progress: PracticeProgress, today: Long): Int =
        (NEW_PER_DAY - if (progress.newDay == today) progress.newToday else 0).coerceAtLeast(0)

    /**
     * Due now: reviews whose time has come, most overdue first, then new items, most
     * recent check first, as many as today's allowance.
     */
    fun due(items: List<PracticeItem>, progress: PracticeProgress, now: Long, today: Long): List<PracticeItem> {
        val reviews = items.filter { progress.states[it.id]?.let { state -> state.due <= now } == true }
            .sortedWith(compareBy<PracticeItem> { progress.states.getValue(it.id).due }.thenBy { it.id })
        val fresh = items.filter { it.id !in progress.states }
            .sortedWith(compareByDescending<PracticeItem> { it.checkedAt }.thenBy { it.id })
            .take(newAllowance(progress, today))
        return reviews + fresh
    }

    /**
     * One run: up to [SESSION_MAX] due items. When some are due but fewer than
     * [SESSION_MIN], the soonest upcoming reviews fill it out. Nothing due, no run.
     */
    fun session(items: List<PracticeItem>, progress: PracticeProgress, now: Long, today: Long): List<PracticeItem> {
        val due = due(items, progress, now, today).take(SESSION_MAX)
        if (due.isEmpty() || due.size >= SESSION_MIN) return due
        val taken = due.mapTo(HashSet()) { it.id }
        val upcoming = items.filter { it.id !in taken && it.id in progress.states }
            .sortedWith(compareBy<PracticeItem> { progress.states.getValue(it.id).due }.thenBy { it.id })
        return due + upcoming.take(SESSION_MIN - due.size)
    }

    /** Records the first answer to [itemId] in a run. */
    fun record(progress: PracticeProgress, itemId: String, grade: Grade, now: Long, today: Long): PracticeProgress {
        val before = progress.states[itemId]
        val started = if (before == null) {
            if (progress.newDay == today) progress.newToday + 1 else 1
        } else if (progress.newDay == today) progress.newToday else 0
        return progress.copy(
            states = progress.states + (itemId to Schedule.review(before, grade, now)),
            newDay = today,
            newToday = started,
        )
    }

    /** Drops state for items that no longer exist: their History entry was deleted. */
    fun prune(progress: PracticeProgress, liveIds: Set<String>): PracticeProgress =
        if (progress.states.keys.all { it in liveIds }) progress
        else progress.copy(states = progress.states.filterKeys { it in liveIds })

    /** When the next review falls due among [items]; null when none has been practised. */
    fun nextDue(items: List<PracticeItem>, progress: PracticeProgress): Long? =
        items.mapNotNull { progress.states[it.id]?.due }.minOrNull()

    /** Whether a picking item shows the corrected sentence first; varies by item and by review. */
    fun correctFirst(item: PracticeItem, progress: PracticeProgress): Boolean {
        val reviews = progress.states[item.id]?.reviews ?: 0
        return (item.id.hashCode() + reviews) and 1 == 0
    }
}
