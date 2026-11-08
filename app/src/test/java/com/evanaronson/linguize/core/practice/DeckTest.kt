package com.evanaronson.linguize.core.practice

import com.evanaronson.linguize.core.practice.Schedule.DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {
    private val now = 1_000_000_000_000L
    private val today = 11_574L

    private fun item(id: String, checkedAt: Long = 0) =
        PracticeItem(id, "check-$id", checkedAt, "es", "Nos vemos despues.", 10, 17, "después")

    private fun state(due: Long) = ReviewState(intervalDays = 1, streak = 1, reviews = 1, due = due, reviewedAt = due - DAY)

    @Test
    fun dueReviewsComeFirstMostOverdueFirstThenNewestNewItems() {
        val items = listOf(item("old", 1), item("new", 5), item("later", 3), item("a"), item("b"))
        val progress = PracticeProgress(states = mapOf(
            "a" to state(now - 1),
            "b" to state(now - 2 * DAY),
            "later" to state(now + DAY),
        ))
        assertEquals(listOf("b", "a", "new", "old"), Deck.due(items, progress, now, today).map { it.id })
    }

    @Test
    fun newItemsAreLimitedPerDay() {
        val items = (1..30).map { item("n$it", it.toLong()) }
        assertEquals(Deck.NEW_PER_DAY, Deck.due(items, PracticeProgress(), now, today).size)
        val someStarted = PracticeProgress(newDay = today, newToday = 7)
        assertEquals(Deck.NEW_PER_DAY - 7, Deck.due(items, someStarted, now, today).size)
        // Yesterday's count doesn't use up today's allowance.
        val yesterday = PracticeProgress(newDay = today - 1, newToday = Deck.NEW_PER_DAY)
        assertEquals(Deck.NEW_PER_DAY, Deck.due(items, yesterday, now, today).size)
        val allStarted = PracticeProgress(newDay = today, newToday = 99)
        assertEquals(0, Deck.due(items, allStarted, now, today).size)
    }

    @Test
    fun aSessionHoldsAtMostTenItems() {
        val items = (1..30).map { item("r$it") }
        val progress = PracticeProgress(states = items.associate { it.id to state(now - 1) })
        assertEquals(Deck.SESSION_MAX, Deck.session(items, progress, now, today).size)
    }

    @Test
    fun aShortSessionIsFilledWithTheSoonestUpcomingReviews() {
        val items = listOf(item("due"), item("soon"), item("sooner"), item("far"), item("x"), item("y"))
        val progress = PracticeProgress(newDay = today, newToday = Deck.NEW_PER_DAY, states = mapOf(
            "due" to state(now - 1),
            "soon" to state(now + 2 * DAY),
            "sooner" to state(now + DAY),
            "far" to state(now + 30 * DAY),
        ))
        // x and y are new, but today's new allowance is used up, so they are not added.
        assertEquals(listOf("due", "sooner", "soon", "far"), Deck.session(items, progress, now, today).map { it.id })
    }

    @Test
    fun nothingDueMeansNoSession() {
        val items = listOf(item("a"), item("b"))
        val progress = PracticeProgress(states = mapOf("a" to state(now + DAY), "b" to state(now + DAY)))
        assertTrue(Deck.session(items, progress, now, today).isEmpty())
        assertTrue(Deck.session(emptyList(), PracticeProgress(), now, today).isEmpty())
    }

    @Test
    fun recordingANewItemCountsItAgainstToday() {
        var progress = PracticeProgress()
        progress = Deck.record(progress, "a", Grade.Good, now, today)
        progress = Deck.record(progress, "b", Grade.Again, now, today)
        assertEquals(2, progress.newToday)
        assertEquals(today, progress.newDay)
        // A review of a known item is not new.
        progress = Deck.record(progress, "a", Grade.Good, now, today)
        assertEquals(2, progress.newToday)
        assertEquals(2, progress.states.getValue("a").reviews)
        // A new day starts the count again.
        progress = Deck.record(progress, "c", Grade.Good, now + DAY, today + 1)
        assertEquals(1, progress.newToday)
        assertEquals(today + 1, progress.newDay)
        progress = Deck.record(progress, "a", Grade.Good, now + 2 * DAY, today + 2)
        assertEquals(0, progress.newToday)
    }

    @Test
    fun aMissedItemIsDueAgainAfterTenMinutes() {
        val items = listOf(item("a"))
        val progress = Deck.record(PracticeProgress(), "a", Grade.Again, now, today)
        assertTrue(Deck.due(items, progress, now + 1, today).isEmpty())
        assertEquals(listOf("a"), Deck.due(items, progress, now + Schedule.RETRY_AFTER, today).map { it.id })
    }

    @Test
    fun pruningDropsStateOfDeletedItems() {
        val progress = PracticeProgress(states = mapOf("a" to state(now), "b" to state(now)), newDay = today, newToday = 2)
        val pruned = Deck.prune(progress, setOf("a", "c"))
        assertEquals(setOf("a"), pruned.states.keys)
        assertEquals(2, pruned.newToday)
        assertSame(pruned, Deck.prune(pruned, setOf("a")))
        assertTrue(Deck.prune(progress, emptySet()).states.isEmpty())
    }

    @Test
    fun nextDueIsTheEarliestOfLiveItems() {
        val items = listOf(item("a"), item("b"))
        assertNull(Deck.nextDue(items, PracticeProgress()))
        val progress = PracticeProgress(states = mapOf("a" to state(now + 3 * DAY), "b" to state(now + DAY), "gone" to state(now)))
        assertEquals(now + DAY, Deck.nextDue(items, progress))
    }

    @Test
    fun choiceOrderChangesBetweenReviews() {
        val item = item("a")
        val before = Deck.correctFirst(item, PracticeProgress())
        val after = Deck.correctFirst(item, PracticeProgress(states = mapOf("a" to state(now))))
        assertNotEquals(before, after)
    }
}
