package com.evanaronson.linguize.core.teachme

import org.junit.Assert.*
import org.junit.Test

class QuizStateTest {
    private val questions = listOf(
        QuizQuestion("Tomorrow: ___ venir.", listOf("Vaig poder", "Podré"), correct = 1, feedback = "Future."),
        QuizQuestion("Yesterday: ___ dormir.", listOf("Vaig poder", "Podré", "Puc"), correct = 0, feedback = "Past."),
    )

    @Test fun startsUnanswered() {
        val quiz = QuizState(questions)
        assertFalse(quiz.finished)
        assertEquals(0, quiz.score)
        assertNull(quiz.choice(0))
        assertNull(quiz.isRight(0))
    }

    @Test fun givesImmediateFeedbackPerQuestion() {
        val quiz = QuizState(questions).choose(0, 1)
        assertEquals(1, quiz.choice(0))
        assertEquals(true, quiz.isRight(0))
        assertNull(quiz.isRight(1))
        assertFalse(quiz.finished)
        val done = quiz.choose(1, 2)
        assertEquals(false, done.isRight(1))
        assertTrue(done.finished)
        assertEquals(1, done.score)
    }

    @Test fun theFirstChoiceLocksTheQuestion() {
        val quiz = QuizState(questions).choose(0, 0).choose(0, 1)
        assertEquals(0, quiz.choice(0))
        assertEquals(false, quiz.isRight(0))
    }

    @Test fun ignoresChoicesOutOfRange() {
        val quiz = QuizState(questions)
        assertSame(quiz, quiz.choose(5, 0))
        assertSame(quiz, quiz.choose(0, 2))
        assertSame(quiz, quiz.choose(1, -1))
    }

    @Test fun resetStartsOver() {
        val done = QuizState(questions).choose(0, 1).choose(1, 0)
        assertEquals(2, done.score)
        val again = done.reset()
        assertFalse(again.finished)
        assertEquals(0, again.score)
        assertEquals(questions, again.questions)
    }

    @Test fun anEmptyQuizIsNeverFinished() {
        assertFalse(QuizState(emptyList()).finished)
    }

    @Test fun teachableTopicsHaveAReasonAndAChange() {
        assertTrue(LessonTopic("vaig", "vaig a", "Missing a").teachable)
        assertTrue(LessonTopic("", "a", "Missing a").teachable)
        assertFalse(LessonTopic("same", " same ", "No change").teachable)
        assertFalse(LessonTopic("a", "b", "  ").teachable)
        assertFalse(LessonTopic(" ", "", "Spacing").teachable)
    }
}
