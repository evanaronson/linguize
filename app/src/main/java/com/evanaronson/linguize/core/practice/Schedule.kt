package com.evanaronson.linguize.core.practice

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** How a review went: wrong (or shown), nearly right, right. */
enum class Grade { Again, Hard, Good }

/** One item's spacing. An item without one is new. */
@Serializable
data class ReviewState(
    /** SM-2 ease factor: how fast the interval grows after a right answer. */
    val ease: Double = Schedule.START_EASE,
    /** Days until the next review, as last scheduled; 0 after a miss. */
    val intervalDays: Int = 0,
    /** Right or nearly right answers in a row. */
    val streak: Int = 0,
    /** Times the item was missed after it had been learned. */
    val lapses: Int = 0,
    val reviews: Int = 0,
    /** Epoch millis from which the item is due. */
    val due: Long,
    val reviewedAt: Long,
)

/**
 * A small SM-2: a miss brings the item back in ten minutes and lowers its ease; a near
 * miss grows the interval slowly; a right answer goes 1 day, 3 days, then by the ease.
 */
object Schedule {
    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0
    const val MAX_INTERVAL_DAYS = 180
    const val DAY = 24L * 60 * 60 * 1000
    const val RETRY_AFTER = 10L * 60 * 1000

    fun review(state: ReviewState?, grade: Grade, now: Long): ReviewState {
        val before = state ?: ReviewState(due = now, reviewedAt = now)
        val reviews = before.reviews + 1
        return when (grade) {
            Grade.Again -> before.copy(
                ease = (before.ease - 0.2).coerceAtLeast(MIN_EASE),
                intervalDays = 0,
                streak = 0,
                lapses = before.lapses + if (before.streak > 0) 1 else 0,
                reviews = reviews,
                due = now + RETRY_AFTER,
                reviewedAt = now,
            )
            Grade.Hard -> {
                val interval = if (before.streak == 0) 1 else (before.intervalDays * 1.2).roundToInt()
                    .coerceAtLeast(before.intervalDays + 1)
                schedule(before, (before.ease - 0.15).coerceAtLeast(MIN_EASE), interval, reviews, now)
            }
            Grade.Good -> {
                val interval = when (before.streak) {
                    0 -> 1
                    1 -> 3
                    else -> (before.intervalDays * before.ease).roundToInt().coerceAtLeast(before.intervalDays + 1)
                }
                schedule(before, (before.ease + 0.05).coerceAtMost(MAX_EASE), interval, reviews, now)
            }
        }
    }

    private fun schedule(before: ReviewState, ease: Double, interval: Int, reviews: Int, now: Long): ReviewState {
        val days = interval.coerceIn(1, MAX_INTERVAL_DAYS)
        return before.copy(
            ease = ease,
            intervalDays = days,
            streak = before.streak + 1,
            reviews = reviews,
            due = now + days * DAY,
            reviewedAt = now,
        )
    }
}
