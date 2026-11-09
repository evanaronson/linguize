package com.evanaronson.linguize.ui.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.time.TimeReport
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Time page: null until read. */
data class TimeState(val report: TimeReport? = null)

/**
 * Lab, Time spent: reads the measurements, first dropping those whose History session is gone
 * (deleted, or History cleared), and builds the [TimeReport]. Estimates are worked out here, on
 * read, from the writer's measured speed.
 */
class TimeSpentViewModel(
    private val store: TimeStore,
    /** Every History session there is; null when History couldn't be read (nothing is swept then). */
    private val sessions: suspend () -> Set<String>?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TimeState())
    val state: StateFlow<TimeState> = mutableState.asStateFlow()

    /** Reads again, for [language] only (Lab, Language scope; null is every language). */
    fun refresh(language: String? = null) {
        viewModelScope.launch { mutableState.value = TimeState(load(language)) }
    }

    internal suspend fun load(language: String? = null): TimeReport {
        val now = clock()
        sessions()?.let { store.sweep(it, now) }
        val records = store.all()
        return withContext(Dispatchers.Default) { TimeReport.of(records, now, language = language) }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { TimeSpentViewModel(app.timeStore, { allSessions(app.history) }) }
        }

        /** Every visible session id in [history], or null when a page couldn't be read. */
        suspend fun allSessions(history: HistoryStore): Set<String>? = withContext(Dispatchers.IO) {
            val ids = HashSet<String>()
            var cursor: HistoryCursor? = null
            do {
                val page = history.page(cursor, PAGE) ?: return@withContext null
                page.rows.mapTo(ids) { it.id }
                cursor = page.next
            } while (cursor != null)
            ids
        }

        private const val PAGE = 500
    }
}
