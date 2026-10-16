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
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SuggestionRecord
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

    /** The saved check could not be read; it may still be available on retry. */
    data object Failed : SessionPage

    data class Shown(
        val session: SessionRecord,
        val appLabel: String?,
        /** Optional read-only Meaning and dependency context, from the same revision as [suggestions]. */
        val card: CardState.Done?,
        /** Why the last attempt failed, if it did. */
        val failure: CheckFailure.Reason?,
        val suggestions: List<SuggestionRecord> = emptyList(),
    ) : SessionPage {
        /** Replay validates contradictory answers; saved status survives unavailable replay. */
        val warning: Verdict.Status?
            get() = when (card?.result) {
                is CheckResult.WrongLanguage -> Verdict.Status.WrongLanguage
                CheckResult.Unclear -> Verdict.Status.Unclear
                else -> session.status?.takeUnless { it == Verdict.Status.Ok }
            }
    }
}

internal enum class SessionDeletion { Ready, Deleting, Failed, Deleted }

/**
 * Loads the past check the detail page shows, and deletes it. [prompt] reads kept answers
 * as checks do; it's called off the main thread (the app's loads from assets on first use).
 * Deleting runs on [appScope] so leaving the page cannot cancel an acknowledged action.
 * The page stays open until storage confirms the deletion, with a retry on failure.
 */
class SessionViewModel internal constructor(
    private val history: HistoryStore,
    private val prompt: () -> Prompt,
    private val labels: AppLabels,
    private val appScope: CoroutineScope,
    private val retireExports: suspend () -> Unit,
) : ViewModel() {
    private var id: String? = null
    private var job: Job? = null

    var page by mutableStateOf<SessionPage>(SessionPage.Loading)
        private set

    internal var deletion by mutableStateOf(SessionDeletion.Ready)
        private set

    /**
     * Shows session [id], read again each time: since it was last shown, its card may have
     * closed or its first save landed. The same session stays on screen while it's reread.
     */
    fun load(id: String) {
        // A host resume must not reread text during or after an acknowledged local delete.
        if (id == this.id && (deletion == SessionDeletion.Deleting || deletion == SessionDeletion.Deleted)) return
        if (id != this.id) {
            page = SessionPage.Loading
            deletion = SessionDeletion.Ready
        }
        this.id = id
        job?.cancel()
        job = viewModelScope.launch {
            page = when (val result = history.detail(id)) {
                SessionDetailResult.Missing -> SessionPage.Missing
                SessionDetailResult.Failed -> SessionPage.Failed
                is SessionDetailResult.Found -> withContext(Dispatchers.IO) {
                    val detail = result.detail
                    val rows = savedSuggestions(detail)
                    // Replay supplies optional read-only context. Never attach a different revision
                    // when saved rows survive a missing raw answer.
                    val sameAttempt = rows.isEmpty() || detail.decidedAttempt() == rows.first().attempt
                    val replayed = if (sameAttempt) replay(detail, prompt()::parseVerdict) else null
                    shown(detail, rows, replayed)
                }
            }
        }
    }

    /** Reads the current check again after a storage failure. */
    fun retry() {
        id?.let(::load)
    }

    /** Keep the check visible until the write succeeds; a failed write can be retried. */
    fun delete() {
        val id = id ?: return
        if (deletion == SessionDeletion.Deleting || deletion == SessionDeletion.Deleted) return
        job?.cancel()
        deletion = SessionDeletion.Deleting
        appScope.launch(Dispatchers.IO) {
            val deleted = history.delete(id)
            // Retire after the row is removed. A separate UI cleanup can run first,
            // allowing another host to recreate an export containing the deleted row.
            retireExports()
            withContext(Dispatchers.Main.immediate) {
                // A different saved check may have opened while this write was pending.
                if (this@SessionViewModel.id == id) {
                    deletion = if (deleted) SessionDeletion.Deleted else SessionDeletion.Failed
                    if (deleted) page = SessionPage.Missing
                }
            }
        }
    }

    /** [replayed] is the card the session ended with ([replay]); null when there's none to show. */
    private fun shown(detail: SessionDetail, rows: List<SuggestionRecord>, replayed: Replayed?): SessionPage.Shown {
        val session = detail.session
        val failure = CheckFailure.Reason.tokens.decode(session.attempts.lastOrNull()?.failure)
        return SessionPage.Shown(session, labels.of(session.hostApp), replayed?.let(::readOnlyCard), failure, rows)
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SessionViewModel(app.history, prompt = { app.prompt }, AppLabels(app.packageManager), app.appScope,
                    retireExports = { app.exports.clear() })
            }
        }
    }
}
