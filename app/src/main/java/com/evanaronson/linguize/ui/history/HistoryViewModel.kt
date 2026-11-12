package com.evanaronson.linguize.ui.history

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.ui.forgetExports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A history row: one recorded message, and the name of the app it came from when that's known.
 * Lab Chat: [chat] is the conversation a chat message was sent in, for its source label only;
 * every row has the same shape and opens its saved check (see HistoryList.kt).
 */
data class RecentRow(
    val summary: SessionSummary,
    val appLabel: String?,
    val chat: com.evanaronson.linguize.core.chat.ChatThreadInfo? = null,
) {
    val key: String get() = summary.id
}

/**
 * The Recent preview and full History list. Deleting hides the row at once; the row is
 * deleted for real once the writer has had the moment to undo. That moment belongs to
 * this view model, not to the screen, so rotating or switching to dark mode while
 * "Check deleted · Undo" shows neither deletes the row early nor loses the Undo.
 * The pending row is also noted on disk, so a process that ends during that moment
 * doesn't lose the deletion: the next Home deletes it.
 */
class HistoryViewModel internal constructor(
    private val app: App,
    private val history: HistoryStore = app.history,
    /** Lab Chat: the conversation of each saved chat message, for its row's label. */
    private val threads: suspend (Collection<String>) -> Map<String, com.evanaronson.linguize.core.chat.ChatThreadInfo> =
        { app.chatStore.threads(it) },
) : ViewModel() {
    private val labels = AppLabels(app.packageManager)

    /** Rows awaiting Undo or an acknowledged deletion; never an archive of deleted IDs. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val pending = MutableStateFlow<String?>(null)
    private val failed = MutableStateFlow<Set<String>>(emptySet())
    private val failureGeneration = MutableStateFlow(0L)

    /** Commits [pending] when no Undo is on screen to answer for it. */
    private var timer: Job? = null

    /** Every screen's pending deletion is durable until that screen commits or undoes it. */
    private val deletions = PendingDeletions(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Five preview rows; hidden IDs are compensated for in the query limit. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: StateFlow<List<RecentRow>?> = hidden.flatMapLatest { excluded ->
        history.recent(PREVIEW_SIZE + excluded.size).map { rows ->
            withChats(rows.filterNot { it.id in excluded }.take(PREVIEW_SIZE).map { RecentRow(it, labels.of(it.hostApp)) })
        }
    }.flowOn(Dispatchers.IO)
        // No replay of a stale list once Home comes back (after Clear, say): null until read again.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    private val pager = HistoryPager(viewModelScope, history.revisions(), history::page) {
        RecentRow(it, labels.of(it.hostApp))
    }

    /** Retained while a check is open, so returning doesn't collapse the loaded list. */
    internal val historyList = combine(pager.state, hidden) { page, excluded ->
        page.copy(rows = withChats(page.rows.filterNot { it.summary.id in excluded }))
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, HistoryListState())

    /**
     * Lab Chat: notes each chat message's conversation, for its "Chat · title" label. Rows stay
     * one per message; a failed lookup leaves them labelled "Chat".
     */
    private suspend fun withChats(rows: List<RecentRow>): List<RecentRow> {
        val ids = rows.filter { it.summary.origin == Origin.Chat }.map { it.summary.id }
        if (ids.isEmpty()) return rows
        val chats = runCatching { threads(ids) }.getOrDefault(emptyMap())
        return rows.map { row -> chats[row.summary.id]?.let { row.copy(chat = it) } ?: row }
    }

    fun showHistory(minimumRows: Int) = pager.start(minimumRows)
    fun loadMore() = pager.loadMore()
    fun retryHistory() = pager.retry()

    /** The row deleted from the list that can still be undone; null when there's none. */
    val pendingDelete: StateFlow<String?> = pending.asStateFlow()

    /** Committed deletions whose row deletion or storage cleanup still needs a retry. */
    val failedDeletes: StateFlow<Set<String>> = failed.asStateFlow()

    /** Every failed completion, even when retry restores the same IDs before a UI frame. */
    internal val failedDeleteGeneration: StateFlow<Long> = failureGeneration.asStateFlow()

    init {
        // A deletion that was waiting for Undo when the last process ended: that Undo is gone.
        // Snapshot it before dispatching work. Reading it later can mistake a deletion
        // made on this new screen for one abandoned by the previous process.
        val abandoned = deletions.recover()
        hidden.update { it + abandoned }
        abandoned.forEach(::deleteCommitted)
    }

    /**
     * Takes [id] off the list at once and deletes it for good unless [undoDelete] comes
     * first. A deletion still pending is committed now: there's one Undo at a time.
     * For a chat message this deletes only its History record: the conversation and the
     * message in it stay in the chat.
     */
    fun delete(id: String) {
        commitDelete()
        hidden.update { it + id }
        pending.value = id
        deletions.add(id)
        startTimer()
    }

    /** Puts the pending row back. Does nothing once [id] is no longer the pending one. */
    fun undoDelete(id: String) {
        if (pending.value != id) return
        timer?.cancel()
        pending.value = null
        hidden.update { it - id }
        deletions.remove(id)
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
        deleteCommitted(pendingId)
    }

    /** Retries committed work without offering another Undo or making hidden rows visible. */
    fun retryFailedDeletes() {
        failed.value.forEach(::deleteCommitted)
    }

    private fun deleteCommitted(id: String) {
        failed.update { it - id }
        // On the app's scope, so it happens even as the screen closes. Any export goes too,
        // since it holds the deleted text.
        app.appScope.launch {
            val deleted = history.delete(id)
            app.forgetExports()
            // Compare and clear on the same thread as delete/undo. An IO-thread
            // read-then-remove could erase a newer pending deletion's durable note.
            withContext(Dispatchers.Main.immediate) {
                if (deleted) deletionCommitted(id) else {
                    failed.update { it + id }
                    failureGeneration.update { it + 1 }
                }
            }
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

    private fun deletionCommitted(id: String) {
        deletions.remove(id)
        pager.remove(id)
        if (pending.value != id) hidden.update { it - id }
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

        const val PREVIEW_SIZE = 5

        /** Recent's own preferences: only the pending deletion. */
        private const val PREFS = "recent"

        /** How long a deletion waits for Undo while no Undo is on screen; about a short snackbar's time. */
        private const val UNDO_MS = 4_000L
    }
}

/**
 * Deletion notes belong to the process, while each screen owns its Undo. All calls are
 * on the main thread so read/modify/write is atomic across multiple Home instances.
 */
internal class PendingDeletions(
    private val prefs: SharedPreferences,
    private val process: String = PROCESS,
) {
    private val owner = UUID.randomUUID().toString()
    private var recovered: Map<String, String> = emptyMap()

    /** Captures only an earlier process's notes, once, before any asynchronous work starts. */
    fun recover(): Set<String> {
        if (prefs.getString(OWNER, null) == process) return emptySet()
        val legacy = prefs.getStringSet(IDS, emptySet()).orEmpty() + listOfNotNull(prefs.getString(LEGACY, null))
        recovered = notes() + legacy.associateBy { noteKey(it) }
        prefs.edit().apply {
            putString(OWNER, process)
            recovered.forEach { (key, id) -> putString(key, id) }
            remove(IDS)
            remove(LEGACY)
        }.apply()
        return recovered.values.toSet()
    }

    fun add(id: String) {
        prefs.edit().putString(noteKey(id), id).apply()
    }

    fun remove(id: String) {
        prefs.edit().apply {
            remove(noteKey(id))
            recovered.filterValues { it == id }.keys.forEach { remove(it) }
        }.apply()
    }

    private fun noteKey(id: String) = "$NOTE_PREFIX$owner:$id"

    private fun notes(): Map<String, String> = prefs.all.entries
        .filter { it.key.startsWith(NOTE_PREFIX) && it.value is String }
        .associate { it.key to it.value as String }

    private companion object {
        val PROCESS = UUID.randomUUID().toString()
        const val OWNER = "pendingProcess"
        const val IDS = "pendingDeletes"
        const val LEGACY = "pendingDelete"
        const val NOTE_PREFIX = "pendingOperation:"
    }
}
