package com.evanaronson.linguize.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeReport
import com.evanaronson.linguize.core.time.UntimedMessage
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.SessionText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Time page: null until read. */
data class TimeState(val report: TimeReport? = null, val language: String? = null)

/**
 * Lab, Time spent: reads the measurements, first dropping those whose History session is gone
 * (deleted, or History cleared), adds the visible History messages that have none (older
 * checks: writing estimated, review not timed), and builds the [TimeReport]. Estimates are
 * worked out here, on read, from the writer's measured speed around each message's time.
 */
class TimeSpentViewModel(
    private val store: TimeStore,
    /** Every visible History session with its text; null when History couldn't be read (nothing is swept or added then). */
    private val history: suspend () -> List<SessionText>?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TimeState())
    val state: StateFlow<TimeState> = mutableState.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null

    /** Reads again, for [language] only (Lab, Language scope; null is every language). */
    fun refresh(language: String? = null) {
        val request = ++generation
        refreshJob?.cancel()
        mutableState.value = TimeState(mutableState.value.report.takeIf { mutableState.value.language == language }, language)
        refreshJob = viewModelScope.launch {
            val report = load(language)
            if (request == generation) mutableState.value = TimeState(report, language)
        }
    }

    internal suspend fun load(language: String? = null): TimeReport {
        val now = clock()
        val saved = history()
        // A superseded History read must not delete measurements using its old session set.
        currentCoroutineContext().ensureActive()
        saved?.let { sessions -> store.sweep(sessions.mapTo(HashSet()) { it.id }, now) }
        val records = store.all()
        return withContext(worker) {
            TimeReport.of(records, now, language = language, untimed = untimed(saved.orEmpty(), records))
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { TimeSpentViewModel(app.timeStore, { app.history.texts() }) }
        }

        /** The sessions in [history] with no record in [records]: counted with estimated writing, review not timed. */
        fun untimed(history: List<SessionText>, records: List<TimeRecord>): List<UntimedMessage> {
            val recorded = records.mapTo(HashSet()) { it.sessionId }
            return history.filter { it.id !in recorded }.map { it.untimed() }
        }
    }
}

/** [this] as a message with no record: its words counted from its whole original text. */
internal fun SessionText.untimed() = UntimedMessage.of(id, origin?.token, language, text, at)
