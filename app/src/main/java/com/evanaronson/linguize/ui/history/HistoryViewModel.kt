package com.evanaronson.linguize.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.ui.forgetExports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row in Recent: the session, and the name of the app it came from when that's known. */
data class RecentRow(val summary: SessionSummary, val appLabel: String?)

/**
 * The Recent list on Home. Deleting from the list hides the row at once; the row is
 * deleted for real once the writer has had the moment to undo. That moment belongs to
 * this view model, not to the screen, so rotating or switching to dark mode while
 * "Check deleted · Undo" shows neither deletes the row early nor loses the Undo.
 * The pending row is also noted on disk, so a process that ends during that moment
 * doesn't lose the deletion: the next Home deletes it.
 */
class HistoryViewModel(private val app: App) : ViewModel() {
    private val labels = AppLabels(app.packageManager)

    /** Rows off the list: the one whose deletion can still be undone, and those deleted since. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val pending = MutableStateFlow<String?>(null)

    /** Commits [pending] when no Undo is on screen to answer for it. */
    private var timer: Job? = null

    /** Where [pending] is noted, until it's deleted or undone. */
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Newest first; null until the first read. */
    val recent: StateFlow<List<RecentRow>?> = combine(
        app.history.recent(LIMIT)
            .map { rows -> rows.map { RecentRow(it, labels.of(it.hostApp)) } }
            .flowOn(Dispatchers.IO),
        hidden,
    ) { rows, hidden -> rows.filterNot { it.summary.id in hidden } }
        // No replay of a stale list once Home comes back (after Clear, say): null until read again.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    /** The row deleted from the list that can still be undone; null when there's none. */
    val pendingDelete: StateFlow<String?> = pending.asStateFlow()

    init {
        // A deletion that was waiting for Undo when the last process ended: that Undo is gone.
        app.appScope.launch(Dispatchers.IO) {
            val id = prefs.getString(PENDING_KEY, null) ?: return@launch
            hidden.update { it + id }
            app.history.delete(id)
            app.forgetExports()
            forgetPending(id)
        }
    }

    /**
     * Takes [id] off the list at once and deletes it for good unless [undoDelete] comes
     * first. A deletion still pending is committed now: there's one Undo at a time.
     */
    fun delete(id: String) {
        commitDelete()
        hidden.update { it + id }
        pending.value = id
        prefs.edit().putString(PENDING_KEY, id).apply()
        startTimer()
    }

    /** Puts the pending row back. Does nothing once [id] is no longer the pending one. */
    fun undoDelete(id: String) {
        if (pending.value != id) return
        timer?.cancel()
        pending.value = null
        hidden.update { it - id }
        forgetPending(id)
    }

    /**
     * Deletes the pending row for good: Undo went away unanswered, or the screen closed.
     * With [id], only if that row is still the pending one.
     */
    fun commitDelete(id: String? = null) {
        val pendingId = pending.value ?: return
        if (id != null && id != pendingId) return
        timer?.cancel()
        pending.value = null
        // On the app's scope, so it happens even as the screen closes. Any export goes too,
        // since it holds the deleted text.
        app.appScope.launch {
            app.history.delete(pendingId)
            app.forgetExports()
            forgetPending(pendingId)
        }
    }

    /** Undo for [id] is on screen: the deletion waits for the writer's answer. */
    fun undoShown(id: String) {
        if (pending.value == id) timer?.cancel()
    }

    /**
     * Undo for [id] left the screen unanswered, because the screen changed or is being
     * recreated. The deletion goes ahead after a moment unless Undo is shown again.
     */
    fun undoHidden(id: String) {
        if (pending.value == id) startTimer()
    }

    /** The screen is gone for good (the activity finished): nothing is left to undo with. */
    override fun onCleared() = commitDelete()

    /** Drops the note of a pending deletion, unless a newer one has replaced it. */
    private fun forgetPending(id: String) {
        if (prefs.getString(PENDING_KEY, null) == id) prefs.edit().remove(PENDING_KEY).apply()
    }

    private fun startTimer() {
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(UNDO_MS)
            commitDelete()
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory { initializer { HistoryViewModel(app) } }

        private const val LIMIT = 50

        /** Recent's own preferences: only the pending deletion. */
        private const val PREFS = "recent"
        private const val PENDING_KEY = "pendingDelete"

        /** How long a deletion waits for Undo while no Undo is on screen; about a short snackbar's time. */
        private const val UNDO_MS = 4_000L
    }
}
