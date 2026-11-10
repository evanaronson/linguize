package com.evanaronson.linguize.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Answers
import com.evanaronson.linguize.core.practice.Deck
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.core.practice.ReviewState
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.HistoryEvidenceReader
import com.evanaronson.linguize.history.HistoryEvidenceWindow
import com.evanaronson.linguize.history.practice.PracticeEvidenceResult
import com.evanaronson.linguize.history.practice.PracticeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** What practice shows: the deck (null until History is read), saved progress, and a run when one is open. */
data class PracticeState(
    val items: List<PracticeItem>? = null,
    val progress: PracticeProgress = PracticeProgress(),
    val now: Long = 0,
    val today: Long = 0,
    val run: PracticeRun? = null,
    /** Lab, Language scope: the app's current language; only its items are due. Null is every language. */
    val language: String? = null,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** This is Practice's retained sample, not a statement that older History no longer exists. */
    val window: HistoryEvidenceWindow? = null,
) {
    /** The items in [language]; an item whose language isn't known is only in an unscoped deck. */
    val scoped: List<PracticeItem>? get() = items?.filter { LanguageScope.keeps(it.language, language) }

    /** Items due now, for "Practice, 4 due" on Progress. */
    val due: Int get() = scoped?.let { Deck.due(it, progress, now, today).size } ?: 0

    /** Lab, Consistency: items in [language] due now, whatever the scope (Progress under Linguize). */
    fun dueIn(language: String): Int =
        items?.filter { it.language == language }?.let { Deck.due(it, progress, now, today).size } ?: 0

    /** When the next review falls due, for the end of a run. */
    val nextDue: Long? get() = scoped?.let { Deck.nextDue(it, progress) }

    /** Lab, Progress IA: when [language]'s next review falls due, whatever the scope (Progress under Linguize). */
    fun nextDueIn(language: String): Long? =
        items?.filter { it.language == language }?.let { Deck.nextDue(it, progress) }

    /** Whether the current picking item shows the corrected sentence first. */
    fun correctFirst(item: PracticeItem): Boolean =
        run?.takeIf { it.current?.id == item.id }?.correctFirst ?: Deck.correctFirst(item, progress)
}

/**
 * Practice drills from the writer's own applied fixes. The deck is rebuilt from History
 * whenever History changes, and scheduling state for checks that were deleted is dropped.
 * No model is asked anything.
 */
class PracticeViewModel internal constructor(
    private val history: HistoryStore,
    private val store: PracticeStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val savedState: SavedStateHandle,
    private val evidence: HistoryEvidenceReader = HistoryEvidenceReader(history),
) : ViewModel() {
    private val source = PracticeSource(evidence)
    private val mutable = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = mutable.asStateFlow()
    private val operations = Mutex()
    private var checkpoint = PracticeCheckpoint.read(savedState[CHECKPOINT])
    private var entry = checkpoint?.entry
    private var requestedEntry: Pair<String, String?>? = null
    private data class PendingGrade(val grade: PracticeGrade, val restored: Boolean = false)
    private var pendingGrade: PendingGrade? = null

    init {
        tick()
        viewModelScope.launch {
            history.revisions().conflate().collect { reload() }
        }
    }

    private suspend fun reload() {
        try {
            operations.withLock {
                val saved = store.read()
                val loaded = source.read(saved.states.keys) as? PracticeEvidenceResult.Ready
                if (loaded == null) {
                    mutable.update { it.copy(loadFailed = true) }
                } else {
                    val items = loaded.items
                    val progress = store.update {
                        // History may have changed while this update waited for the progress
                        // writer. An older sample cannot retire a newer item's schedule.
                        if (loaded.revision != null && evidence.revision() != loaded.revision) {
                            throw IOException("History changed before updating practice progress")
                        }
                        Deck.prune(it, items.mapTo(HashSet()) { item -> item.id })
                    }
                    mutable.update { it.copy(items = items, progress = progress, loadFailed = false, window = loaded.window) }
                    checkpoint?.let { saved ->
                        val restored = saved.run?.restore(items, progress, saved.pending)
                        pendingGrade = restored?.pending?.let { PendingGrade(it, restored = true) }
                        mutable.update { it.copy(run = restored?.run, saveFailed = pendingGrade != null) }
                        checkpoint = null
                        rememberRun()
                    }
                }
                tick()
                if (loaded != null) requestedEntry?.let { (id, language) ->
                    requestedEntry = null
                    enter(language, id)
                }
            }
        } catch (_: IOException) {
            mutable.update { it.copy(loadFailed = true) }
        }
    }

    fun retryLoad() { viewModelScope.launch { reload() } }

    /** Brings due counts up to date with the clock; Home calls it when shown. */
    fun tick() {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toEpochDay()
        mutable.update { it.copy(now = now, today = today) }
    }

    /** Same page identity restores its run; a newly entered page may start the next due run. */
    fun enter(language: String?, entryId: String) {
        setLanguage(language)
        if (state.value.items == null || checkpoint != null) {
            requestedEntry = entryId to language
            return
        }
        val sameEntry = entry == entryId
        entry = entryId
        val run = state.value.run
        if (run == null || (!sameEntry && run.finished)) start() else rememberRun()
    }

    /** Leaving while History is loading must not start a hidden run or reset Progress's scope. */
    fun leaveEntry(entryId: String) {
        if (requestedEntry?.first == entryId) requestedEntry = null
    }

    /** Opens a run of the items due now. Does nothing when none are. */
    fun start() {
        if (pendingGrade != null || checkpoint != null) return
        tick()
        mutable.update { state ->
            val items = state.scoped ?: return@update state
            val queue = Deck.session(items, state.progress, state.now, state.today)
            state.copy(run = if (queue.isEmpty()) state.run else PracticeRun(
                queue, language = state.language,
                baseline = queue.associate { it.id to state.progress.states[it.id] },
                correctFirst = Deck.correctFirst(queue.first(), state.progress),
            ))
        }
        rememberRun()
    }

    /** Checks a typed fix. */
    fun submit(typed: String) {
        val item = state.value.run?.current ?: return
        if (typed.isBlank()) return
        answer(Answered(Answers.check(item, typed)))
    }

    /** Checks a pick between the sentence as written and corrected. */
    fun choose(correct: Boolean) {
        answer(Answered(if (correct) AnswerResult.Correct else AnswerResult.Wrong, pickedCorrect = correct))
    }

    /** Shows the fix without an answer; counts as a miss. */
    fun reveal() {
        answer(Answered(AnswerResult.Wrong, revealed = true))
    }

    private fun answer(answer: Answered) {
        if (pendingGrade != null) return
        val before = state.value
        val run = before.run ?: return
        val id = run.current?.id ?: return
        val (next, grade) = run.answer(answer)
        // Feedback stays immediate; the schedule and Next wait for durable acknowledgement.
        mutable.value = before.copy(run = next)
        pendingGrade = grade?.let {
            PendingGrade(PracticeGrade(id, it.name, before.now, before.today, before.progress.states[id]))
        }
        rememberRun()
        if (pendingGrade != null) retrySave()
    }

    /** Retries exactly the first grade, without counting the answer twice or losing its timestamp. */
    fun retrySave() {
        val (pending, conditional) = pendingGrade ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                var recorded = false
                var prior: ReviewState? = null
                val progress = operations.withLock {
                    // A refresh may have pruned this item while the failed write waited for Retry.
                    val stillInDeck = state.value.items?.any { it.id == pending.id } == true
                    store.update {
                        // Fresh live runs retain sequential grading. Only a command recovered
                        // from an older checkpoint may already have committed before recreation.
                        if (stillInDeck && (!conditional || it.states[pending.id] == pending.before)) {
                            prior = it.states[pending.id]
                            recorded = true
                            Deck.record(it, pending.id, pending.value, pending.now, pending.today)
                        } else it
                    }
                }
                pendingGrade = null
                mutable.update { state ->
                    var run = if (recorded) state.run?.let { it.copy(baseline = it.baseline + (pending.id to prior)) } else state.run
                    if (conditional && run != null) {
                        // Another host may have advanced the schedule after reconstruction.
                        // Reconcile a skipped retry before treating its feedback as acknowledged.
                        run = SavedPracticeRun.of(run).restore(state.items.orEmpty(), progress, pending).run
                    }
                    state.copy(run = run, progress = progress, saving = false, saveFailed = false)
                }
                rememberRun()
            } catch (_: IOException) {
                mutable.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Lab, Language scope: practice only [language]'s items. An open run keeps going. */
    fun setLanguage(language: String?) {
        mutable.update { it.copy(language = language) }
    }

    fun next() {
        if (pendingGrade != null) return
        mutable.update { state ->
            val next = state.run?.next() ?: return@update state
            if (next === state.run) state else state.copy(run = next.copy(
                correctFirst = next.current?.let { Deck.correctFirst(it, state.progress) },
            ))
        }
        rememberRun()
    }

    /** Closes the run; an uncommitted answer keeps its run available when the writer returns. */
    fun close() {
        if (pendingGrade != null) return
        mutable.update { it.copy(run = null) }
        rememberRun()
        tick()
    }

    private fun rememberRun() {
        savedState[CHECKPOINT] = PracticeCheckpoint(
            entry = entry, run = state.value.run?.let(SavedPracticeRun::of), pending = pendingGrade?.grade,
        ).encode()
    }

    companion object {
        private const val CHECKPOINT = "practice_run"
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PracticeViewModel(app.history, PracticeStore(File(app.filesDir, PracticeStore.FILE)),
                    savedState = createSavedStateHandle(), evidence = app.historyEvidence)
            }
        }
    }
}
