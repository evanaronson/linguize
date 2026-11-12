package com.evanaronson.linguize.ui.teachme

import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonExample
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.core.teachme.QuizQuestion
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

internal val SAMPLE_LESSON = Lesson(
    language = "Catalan",
    rule = "The periphrastic past uses vaig + infinitive.",
    yours = "Vaig poder is past; you meant the future.",
    examples = List(3) { LessonExample("Frase $it.", "Sentence $it.") },
    trap = "Vaig looks like 'I go'.",
    quiz = listOf(
        QuizQuestion("Q1", listOf("a", "b"), 1, "Because b."),
        QuizQuestion("Q2", listOf("c", "d", "e"), 0, "Because c."),
    ),
)

class LessonControllerTest {
    private val topic = LessonTopic("vaig poder", "podré", "Future, not past")
    // Unconfined: launches run straight away on the test thread up to their first suspension.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    @Test fun opensToALessonAndAnswersTheQuizInPlace() {
        val asked = mutableListOf<LessonTopic>()
        val controller = LessonController(topic, scope) { asked += it; SAMPLE_LESSON }
        assertEquals(LessonState.Closed, controller.state)
        controller.open()
        val ready = controller.state as LessonState.Ready
        assertEquals(listOf(topic), asked)
        assertEquals(SAMPLE_LESSON, ready.lesson)
        assertFalse(ready.quiz.finished)

        controller.choose(0, 0)
        controller.choose(0, 1) // Locked: the first answer counts.
        controller.choose(1, 0)
        val answered = (controller.state as LessonState.Ready).quiz
        assertEquals(0, answered.choice(0))
        assertEquals(false, answered.isRight(0))
        assertEquals(true, answered.isRight(1))
        assertTrue(answered.finished)
        assertEquals(1, answered.score)

        controller.again()
        val again = (controller.state as LessonState.Ready).quiz
        assertFalse(again.finished)
        assertNull(again.choice(0))

        controller.open() // Already open: no second request.
        assertEquals(1, asked.size)
        controller.close()
        assertEquals(LessonState.Closed, controller.state)
    }

    @Test fun showsLoadingUntilTheLessonArrives() {
        val gate = CompletableDeferred<Lesson>()
        val controller = LessonController(topic, scope) { gate.await() }
        controller.open()
        assertEquals(LessonState.Loading, controller.state)
        controller.choose(0, 0) // Nothing to answer yet.
        assertEquals(LessonState.Loading, controller.state)
        gate.complete(SAMPLE_LESSON)
        assertTrue(controller.state is LessonState.Ready)
    }

    @Test fun aFailureCanBeRetried() {
        var attempts = 0
        val controller = LessonController(topic, scope) {
            attempts++
            if (attempts == 1) throw CheckFailure(CheckFailure.Reason.Offline)
            SAMPLE_LESSON
        }
        controller.open()
        assertEquals(LessonState.Failed(CheckFailure.Reason.Offline), controller.state)
        controller.retry()
        assertTrue(controller.state is LessonState.Ready)
        assertEquals(2, attempts)
    }

    @Test fun anUnexpectedErrorIsAnUnusableReply() {
        val controller = LessonController(topic, scope) { throw IllegalStateException("boom") }
        controller.open()
        assertEquals(LessonState.Failed(CheckFailure.Reason.BadResponse), controller.state)
    }

    @Test fun keepsTheFailuresOwnMessage() {
        val controller = LessonController(topic, scope) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)
        }
        controller.open()
        assertEquals(LessonState.Failed(CheckFailure.Reason.BadResponse, CheckFailure.Message.InvalidReply), controller.state)
    }

    @Test fun aLessonThatArrivesAfterCloseIsDropped() {
        val gate = CompletableDeferred<Lesson>()
        val controller = LessonController(topic, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
            // Ignores cancellation, like a request already on the wire.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
        }
        controller.open()
        controller.close()
        gate.complete(SAMPLE_LESSON)
        assertEquals(LessonState.Closed, controller.state)
    }

    @Test fun retryAndAgainDoNothingInTheWrongState() {
        val controller = LessonController(topic, scope) { SAMPLE_LESSON }
        controller.retry()
        controller.again()
        assertEquals(LessonState.Closed, controller.state)
    }
}
