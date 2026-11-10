package com.evanaronson.linguize.ui.practice

import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.ReviewState
import java.util.UUID

/** The answer to the item on screen. [revealed] when the writer asked to see the fix instead. */
data class Answered(val result: AnswerResult, val revealed: Boolean = false, val pickedCorrect: Boolean? = null)

/**
 * One practice run, start to finish. A missed item comes back once at the end of the run;
 * only the first answer to each item counts for scheduling.
 */
data class PracticeRun(
    val queue: List<PracticeItem>,
    val index: Int = 0,
    /** Null while the current item waits for an answer. */
    val answered: Answered? = null,
    /** The first answer's grade for each item, by id. */
    val grades: Map<String, Grade> = emptyMap(),
    /** Items already sent round again. */
    val repeated: Set<String> = emptySet(),
    /** Identity and small decisions needed to rebuild this run from History after recreation. */
    val id: String = UUID.randomUUID().toString(),
    val language: String? = null,
    val baseline: Map<String, ReviewState?> = emptyMap(),
    val correctFirst: Boolean? = null,
) {
    val current: PracticeItem? get() = queue.getOrNull(index)
    val finished: Boolean get() = index >= queue.size

    /** Distinct items in the run. */
    val size: Int get() = queue.size - repeated.size

    /** Items answered right or nearly right the first time. */
    val right: Int get() = grades.values.count { it != Grade.Again }

    /** 1-based position for "3 of 7", counting repeats as extra steps. */
    val position: Int get() = (index + 1).coerceAtMost(queue.size)
    val steps: Int get() = queue.size

    /**
     * Records [answer] for the current item. Returns the new run and, the first time this
     * item is answered in the run, the grade to schedule it with.
     */
    fun answer(answer: Answered): Pair<PracticeRun, Grade?> {
        val item = current ?: return this to null
        if (answered != null) return this to null
        val grade = if (answer.revealed) Grade.Again else answer.result.grade
        val first = item.id !in grades
        val run = copy(answered = answer, grades = if (first) grades + (item.id to grade) else grades)
        return run to grade.takeIf { first }
    }

    /** Moves on, sending a missed item round once more. Only after an answer. */
    fun next(): PracticeRun {
        val item = current ?: return this
        val answer = answered ?: return this
        val missed = answer.revealed || answer.result.grade == Grade.Again
        val again = missed && item.id !in repeated
        return copy(
            queue = if (again) queue + item else queue,
            repeated = if (again) repeated + item.id else repeated,
            index = index + 1,
            answered = null,
        )
    }
}
