package com.evanaronson.linguize.ui.teachme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.core.teachme.QuizState
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where one "Teach me" stands. */
sealed interface LessonState {
    /** Only the "Teach me" link shows. */
    data object Closed : LessonState

    data object Loading : LessonState

    data class Ready(val lesson: Lesson, val quiz: QuizState) : LessonState

    data class Failed(val reason: CheckFailure.Reason, val message: CheckFailure.Message? = null) : LessonState
}

/**
 * One explanation's lesson: [open] shows a saved one at once or asks for a new one, and the
 * quiz is answered in place. A lesson that arrives after [close] (or after a newer [open])
 * is dropped. [teach] throws [CheckFailure]; [scope] is the composition's.
 */
class LessonController(
    private val topic: LessonTopic,
    private val scope: CoroutineScope,
    private val teach: suspend (LessonTopic) -> Lesson,
) {
    var state by mutableStateOf<LessonState>(LessonState.Closed)
        private set

    private var job: Job? = null
    private var generation = 0

    fun open() {
        if (state is LessonState.Ready || state is LessonState.Loading) return
        val asked = ++generation
        state = LessonState.Loading
        job?.cancel()
        job = scope.launch {
            val next = try {
                val lesson = teach(topic)
                LessonState.Ready(lesson, QuizState(lesson.quiz))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                LessonState.Failed(e.reason, e.userMessage)
            } catch (e: Exception) {
                LessonState.Failed(CheckFailure.Reason.BadResponse)
            }
            if (asked == generation) state = next
        }
    }

    fun retry() {
        if (state is LessonState.Failed) {
            state = LessonState.Closed
            open()
        }
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        state = LessonState.Closed
    }

    fun choose(question: Int, option: Int) {
        val ready = state as? LessonState.Ready ?: return
        state = ready.copy(quiz = ready.quiz.choose(question, option))
    }

    /** Starts the quiz over. */
    fun again() {
        val ready = state as? LessonState.Ready ?: return
        state = ready.copy(quiz = ready.quiz.reset())
    }
}
