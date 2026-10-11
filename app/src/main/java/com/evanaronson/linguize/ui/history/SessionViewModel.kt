package com.evanaronson.linguize.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.replay
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One past check, as its page shows it. */
sealed interface SessionPage {
    data object Loading : SessionPage

    /** Deleted, or never kept. */
    data object Missing : SessionPage

    data class Shown(
        val session: SessionRecord,
        val appLabel: String?,
        /** The last successful answer, rebuilt as the card showed it; null when none can be shown. */
        val card: CardState.Done?,
        /** Why the last attempt failed, if it did. */
        val failure: CheckFailure.Reason?,
    ) : SessionPage
}

/**
 * Loads the past check the detail page shows, and deletes it. [prompt] reads kept answers
 * as checks do; it's called off the main thread (the app's loads from assets on first use).
 * Deleting runs on [appScope], since the page closes straight away.
 */
class SessionViewModel internal constructor(
    private val history: HistoryStore,
    private val prompt: () -> Prompt,
    private val labels: AppLabels,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private var id: String? = null
    private var job: Job? = null

    var page by mutableStateOf<SessionPage>(SessionPage.Loading)
        private set

    /**
     * Shows session [id], read again each time: since it was last shown, its card may have
     * closed or its first save landed. The same session stays on screen while it's reread.
     */
    fun load(id: String) {
        if (id != this.id) page = SessionPage.Loading
        this.id = id
        job?.cancel()
        job = viewModelScope.launch {
            val detail = history.detail(id)
            page = if (detail == null) {
                SessionPage.Missing
            } else {
                withContext(Dispatchers.IO) {
                    val read = prompt()
                    shown(detail, replay(detail, read::parseVerdict))
                }
            }
        }
    }

    /** Deletes the session shown. The page closes straight away, so on the app's scope. */
    fun delete() {
        val id = id ?: return
        this.id = null
        appScope.launch { history.delete(id) }
    }

    /** [replayed] is the card the session ended with ([replay]); null when there's none to show. */
    private fun shown(detail: SessionDetail, replayed: Replayed?): SessionPage.Shown {
        val session = detail.session
        val failure = CheckFailure.Reason.tokens.decode(session.attempts.lastOrNull()?.failure)
        return SessionPage.Shown(session, labels.of(session.hostApp), replayed?.let(::readOnlyCard), failure)
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(app.history, prompt = { app.prompt }, AppLabels(app.packageManager), app.appScope) }
        }
    }
}
