package com.evanaronson.linguize.ui.practice

import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.Hint
import com.evanaronson.linguize.core.practice.PracticeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeRunTest {
    private fun item(id: String) = PracticeItem(id, "c", 0, "es", "Nos vemos despues.", 10, 17, "después")

    @Test
    fun aRightRunEndsAfterEachItemOnce() {
        var run = PracticeRun(listOf(item("a"), item("b")))
        assertEquals(1, run.position)
        val (answered, grade) = run.answer(Answered(AnswerResult.Correct))
        assertEquals(Grade.Good, grade)
        run = answered.next()
        assertEquals(2, run.position)
        run = run.answer(Answered(AnswerResult.Almost(Hint.Punctuation))).first.next()
        assertTrue(run.finished)
        assertEquals(2, run.right)
        assertEquals(2, run.size)
    }

    @Test
    fun aMissComesBackOnceAndOnlyTheFirstAnswerCounts() {
        var run = PracticeRun(listOf(item("a"), item("b")))
        val (missed, grade) = run.answer(Answered(AnswerResult.Wrong))
        assertEquals(Grade.Again, grade)
        run = missed.next()
        assertEquals(3, run.steps)
        assertEquals(2, run.size)
        run = run.answer(Answered(AnswerResult.Correct)).first.next()
        assertEquals("a", run.current?.id)
        val (again, secondGrade) = run.answer(Answered(AnswerResult.Wrong))
        assertNull(secondGrade)
        run = again.next()
        // Missed twice still comes back only once.
        assertTrue(run.finished)
        assertEquals(1, run.right)
        assertEquals(Grade.Again, run.grades["a"])
    }

    @Test
    fun revealingCountsAsAMiss() {
        val run = PracticeRun(listOf(item("a")))
        val (revealed, grade) = run.answer(Answered(AnswerResult.Wrong, revealed = true))
        assertEquals(Grade.Again, grade)
        assertEquals(2, revealed.next().steps)
    }

    @Test
    fun anItemIsAnsweredOnceAndNextNeedsAnAnswer() {
        val run = PracticeRun(listOf(item("a")))
        assertSame(run, run.next())
        val answered = run.answer(Answered(AnswerResult.Correct)).first
        val (same, grade) = answered.answer(Answered(AnswerResult.Wrong))
        assertSame(answered, same)
        assertNull(grade)
        assertFalse(answered.finished)
    }
}
