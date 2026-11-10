package com.evanaronson.linguize.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Answers
import com.evanaronson.linguize.core.practice.Deck
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.HistoryStore
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
    fun correctFirst(item: PracticeItem): Boolean = Deck.correctFirst(item, progress)
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
) : ViewModel() {
    private val source = PracticeSource(history)
    private val mutable = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = mutable.asStateFlow()
    private val operations = Mutex()
    private data class PendingGrade(val id: String, val grade: Grade, val now: Long, val today: Long)
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
                val items = source.load(saved.states.keys)
                if (items == null) {
                    mutable.update { it.copy(loadFailed = true) }
                } else {
                    val progress = store.update { Deck.prune(it, items.mapTo(HashSet()) { item -> item.id }) }
                    mutable.update { it.copy(items = items, progress = progress, loadFailed = false) }
                }
                tick()
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

    /** Opens a run of the items due now. Does nothing when none are. */
    fun start() {
        if (pendingGrade != null) return
        tick()
        mutable.update { state ->
            val items = state.scoped ?: return@update state
            val queue = Deck.session(items, state.progress, state.now, state.today)
            state.copy(run = PracticeRun(queue).takeIf { queue.isNotEmpty() } ?: state.run)
        }
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
        pendingGrade = grade?.let { PendingGrade(id, it, before.now, before.today) } ?: return
        retrySave()
    }

    /** Retries exactly the first grade, without counting the answer twice or losing its timestamp. */
    fun retrySave() {
        val pending = pendingGrade ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val progress = operations.withLock {
                    // A refresh may have pruned this item while the failed write waited for Retry.
                    val stillInDeck = state.value.items?.any { it.id == pending.id } == true
                    store.update {
                        if (stillInDeck) Deck.record(it, pending.id, pending.grade, pending.now, pending.today) else it
                    }
                }
                pendingGrade = null
                mutable.update { it.copy(progress = progress, saving = false, saveFailed = false) }
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
        mutable.update { state -> state.copy(run = state.run?.next()) }
    }

    /** Closes the run; an uncommitted answer keeps its run available when the writer returns. */
    fun close() {
        if (pendingGrade != null) return
        mutable.update { it.copy(run = null) }
        tick()
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { PracticeViewModel(app.history, PracticeStore(File(app.filesDir, PracticeStore.FILE))) }
        }
    }
}
