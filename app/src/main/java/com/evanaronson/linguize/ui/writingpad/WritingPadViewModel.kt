package com.evanaronson.linguize.ui.writingpad

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.writingpad.CheckSchedule
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import com.evanaronson.linguize.core.writingpad.PadEngine
import com.evanaronson.linguize.data.writingpad.Draft
import com.evanaronson.linguize.data.writingpad.DraftStore
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What the status line says about the text in the editor. */
sealed interface PadStatus {
    /** Nothing worth checking yet. */
    data object Empty : PadStatus

    /** Changed since the last check; one starts when the writer pauses. */
    data object Waiting : PadStatus
    data object Checking : PadStatus
    data object TooLong : PadStatus
    data class Found(val fixes: Int, val rewordings: Int) : PadStatus
    data object SoundsNatural : PadStatus
    data object Unclear : PadStatus
    data class WrongLanguage(val expected: String) : PadStatus
    data class Failed(val failure: CheckFailure) : PadStatus
}

/** The open draft as the editor shows it. */
data class PadEditor(
    val draftId: String,
    val title: String,
    val value: TextFieldValue,
    /** A language code, or null for auto-detect. */
    val language: String?,
    val suggestions: List<LiveSuggestion>,
    /** The suggestions under the cursor, when the writer tapped one: the panel shows them. */
    val focused: List<LiveSuggestion>,
    val meaning: String?,
    /** Whether [meaning] was read from the text as it is now. */
    val meaningCurrent: Boolean,
    val status: PadStatus,
)

/**
 * The Writing pad: a list of drafts and one open draft, checked as the writer pauses.
 * [PadEngine] holds the rules; this drives its timer and checks on [viewModelScope], and
 * autosaves to [store] on [io]. [clock] is the main looper's clock, which [delay] follows.
 */
class WritingPadViewModel(
    private val store: DraftStore,
    private val check: suspend (String, Language?) -> CheckResult,
    private val idleMillis: Long = CheckSchedule.DEFAULT_IDLE_MILLIS,
    private val saveMillis: Long = SAVE_MILLIS,
    private val maxChars: Int = Checker.MAX_CHARS,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /** Every draft, newest first; null until first read. */
    var drafts by mutableStateOf<List<Draft>?>(null)
        private set
    var editor by mutableStateOf<PadEditor?>(null)
        private set

    private val storeLock = Mutex()
    private var engine: PadEngine? = null
    private var value = TextFieldValue()
    private var title = ""
    private var language: String? = null
    private var draftId: String? = null
    private var showingPanel = false
    private var failure: CheckFailure? = null
    private var timer: Job? = null
    private var running: Job? = null
    private var saving: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { drafts = stored { it.all() } }
    }

    /** Starts a new draft and opens it. */
    fun newDraft() {
        viewModelScope.launch {
            val draft = stored { it.create() }
            drafts = stored { it.all() }
            show(draft)
        }
    }

    fun open(id: String) {
        viewModelScope.launch { stored { it.get(id) }?.let(::show) }
    }

    /** Saves and closes the open draft; an empty one is deleted rather than listed. */
    fun close() {
        val id = draftId ?: return
        val empty = title.isBlank() && value.text.isBlank()
        val saved = Triple(title, value.text, language)
        stopEditor()
        viewModelScope.launch {
            stored { if (empty) it.delete(id) else it.save(id, saved.first, saved.second, saved.third) }
            drafts = stored { it.all() }
        }
    }

    /** Deletes draft [id], then hands it to [onDeleted] so the deletion can be undone with [restore]. */
    fun delete(id: String, onDeleted: (Draft) -> Unit = {}) {
        val open = if (id == draftId) Triple(title, value.text, language) else null
        if (open != null) stopEditor()
        viewModelScope.launch {
            // Undo brings back the open draft as it was on screen, not as last autosaved.
            open?.let { (title, text, language) -> stored { it.save(id, title, text, language) } }
            val draft = stored { it.get(id) } ?: return@launch
            stored { it.delete(id) }
            drafts = stored { it.all() }
            onDeleted(draft)
        }
    }

    fun restore(draft: Draft) {
        viewModelScope.launch {
            stored { it.restore(draft) }
            drafts = stored { it.all() }
        }
    }

    /** Saves the open draft now, as when the app goes to the background. */
    fun flush() {
        val id = draftId ?: return
        saving?.cancel()
        val snapshot = Triple(title, value.text, language)
        viewModelScope.launch { stored { it.save(id, snapshot.first, snapshot.second, snapshot.third) } }
    }

    fun onTitleChange(newTitle: String) {
        if (draftId == null || newTitle == title) return
        title = newTitle
        scheduleSave()
        publish()
    }

    /** The text field changed: typing, or only the cursor or selection moving. */
    fun onValueChange(newValue: TextFieldValue) {
        val engine = engine ?: return
        val edited = newValue.text != value.text
        val moved = newValue.selection != value.selection
        value = newValue
        if (edited) {
            engine.edit(newValue.text, clock())
            showingPanel = false
            scheduleSave()
            reschedule()
        } else if (moved) {
            // A tap on an underline puts the cursor in it: show that change.
            showingPanel = newValue.selection.collapsed && engine.at(newValue.selection.start).isNotEmpty()
        }
        publish()
    }

    /** Moves the cursor to the next change after it, wrapping around, and shows it. */
    fun nextChange() {
        val engine = engine ?: return
        val ordered = engine.suggestions.sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
        if (ordered.isEmpty()) return
        val cursor = value.selection.start
        val current = if (showingPanel) engine.at(cursor).firstOrNull() else null
        val next = if (current == null) {
            ordered.firstOrNull { it.covers(cursor) } ?: ordered.firstOrNull { it.start > cursor } ?: ordered.first()
        } else {
            ordered.firstOrNull { it.start > current.start } ?: ordered.first()
        }
        value = value.copy(selection = TextRange(next.start))
        showingPanel = true
        publish()
    }

    /** Shows suggestion [key], as a screen reader's action on the text does. */
    fun focus(key: Long) {
        val suggestion = engine?.suggestions?.firstOrNull { it.key == key } ?: return
        value = value.copy(selection = TextRange(suggestion.start))
        showingPanel = true
        publish()
    }

    fun closePanel() {
        showingPanel = false
        publish()
    }

    fun accept(key: Long) {
        val engine = engine ?: return
        val cursor = engine.accept(key, clock()) ?: return
        value = TextFieldValue(engine.text, TextRange(cursor))
        showingPanel = false
        scheduleSave()
        reschedule()
        publish()
    }

    fun dismiss(key: Long) {
        val engine = engine ?: return
        engine.dismiss(key)
        showingPanel = engine.at(value.selection.start).isNotEmpty() && showingPanel
        publish()
    }

    /** Checks with [code] (null to detect the language) from now on, starting with the text as it is. */
    fun setLanguage(code: String?) {
        if (draftId == null) return
        language = code
        startEngine(value.text)
        scheduleSave()
        publish()
    }

    fun retry() {
        val engine = engine ?: return
        failure = null
        engine.retry(clock())?.let(::start)
        publish()
    }

    private fun show(draft: Draft) {
        stopEditor()
        draftId = draft.id
        title = draft.title
        language = draft.language
        value = TextFieldValue(draft.text, TextRange(draft.text.length))
        startEngine(draft.text)
        publish()
    }

    private fun startEngine(text: String) {
        engine?.stop()
        running?.cancel()
        timer?.cancel()
        failure = null
        showingPanel = false
        engine = PadEngine(text, idleMillis, maxChars)
        reschedule()
    }

    private fun stopEditor() {
        saving?.cancel()
        timer?.cancel()
        running?.cancel()
        engine?.stop()
        engine = null
        draftId = null
        failure = null
        showingPanel = false
        editor = null
    }

    /** Waits for the pause, then starts the check that is due. */
    private fun reschedule() {
        timer?.cancel()
        val engine = engine ?: return
        val wake = engine.schedule.wakeAt() ?: return
        timer = viewModelScope.launch {
            delay((wake - clock()).coerceAtLeast(0))
            if (this@WritingPadViewModel.engine === engine) engine.due(clock())?.let(::start)
            publish()
        }
    }

    private fun start(start: CheckSchedule.Start) {
        val engine = engine ?: return
        // Only one check runs: the newer text makes the older answer stale.
        if (start.cancelled != null) running?.cancel()
        val request = start.request
        val language = Language.forCode(language)
        running = viewModelScope.launch {
            try {
                val result = check(request.text, language)
                if (engine.received(request, result)) failure = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                if (engine.failed(request)) failure = e
            } catch (e: Exception) {
                if (engine.failed(request)) failure = CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
            }
            if (this@WritingPadViewModel.engine !== engine) return@launch
            showingPanel = showingPanel && engine.at(value.selection.start).isNotEmpty()
            // The writer may have typed while it ran.
            reschedule()
            publish()
        }
        publish()
    }

    private fun scheduleSave() {
        val id = draftId ?: return
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(saveMillis)
            val saved = stored { it.save(id, title, value.text, language) }
            if (saved != null) drafts = stored { it.all() }
        }
    }

    private fun publish() {
        val engine = engine
        val id = draftId
        if (engine == null || id == null) {
            editor = null
            return
        }
        // The text field is the source of truth for the text; the engine follows it exactly.
        if (engine.text != value.text) value = value.copy(text = engine.text)
        editor = PadEditor(
            draftId = id,
            title = title,
            value = value,
            language = language,
            suggestions = engine.suggestions,
            focused = if (showingPanel && value.selection.collapsed) engine.at(value.selection.start) else emptyList(),
            meaning = engine.meaning?.text,
            meaningCurrent = engine.meaningIsCurrent,
            status = status(engine),
        )
    }

    private fun status(engine: PadEngine): PadStatus {
        val schedule = engine.schedule
        return when {
            engine.tooLong -> PadStatus.TooLong
            engine.text.count { it.isLetter() } < PadEngine.MIN_LETTERS -> PadStatus.Empty
            schedule.running != null -> PadStatus.Checking
            schedule.pending -> PadStatus.Waiting
            failure != null && schedule.failed == engine.text -> PadStatus.Failed(failure!!)
            else -> when (val outcome = engine.outcome) {
                PadEngine.Outcome.None -> PadStatus.Waiting
                PadEngine.Outcome.Unclear -> PadStatus.Unclear
                is PadEngine.Outcome.WrongLanguage -> PadStatus.WrongLanguage(outcome.expected)
                PadEngine.Outcome.Reviewed -> {
                    val fixes = engine.count(EditKind.Fix)
                    val rewordings = engine.count(EditKind.Natural)
                    if (fixes + rewordings == 0) PadStatus.SoundsNatural else PadStatus.Found(fixes, rewordings)
                }
            }
        }
    }

    private suspend fun <T> stored(block: (DraftStore) -> T): T = storeLock.withLock { withContext(io) { block(store) } }

    override fun onCleared() {
        // The scope is gone; save what is open synchronously-enough on the app's IO pool.
        val id = draftId ?: return
        val snapshot = Triple(title, value.text, language)
        Thread {
            runCatching {
                if (snapshot.first.isBlank() && snapshot.second.isBlank()) store.delete(id)
                else store.save(id, snapshot.first, snapshot.second, snapshot.third)
            }
        }.start()
    }

    companion object {
        /** Autosave after this much quiet. */
        const val SAVE_MILLIS = 600L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WritingPadViewModel(
                    store = DraftStore(File(app.filesDir, DraftStore.FILE_NAME)),
                    // Pad checks aren't recorded in History, like checks in Try it.
                    check = { text, language ->
                        withContext(Dispatchers.Default) { app.checker }.check(text, language).result
                    },
                )
            }
        }
    }
}
