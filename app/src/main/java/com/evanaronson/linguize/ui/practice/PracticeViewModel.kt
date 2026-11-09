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
import java.io.File
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
) {
    /** The items in [language]; an item whose language isn't known is only in an unscoped deck. */
    val scoped: List<PracticeItem>? get() = items?.filter { LanguageScope.keeps(it.language, language) }

    /** Items due now, for "Practice, 4 due" on Progress. */
    val due: Int get() = scoped?.let { Deck.due(it, progress, now, today).size } ?: 0

    /** When the next review falls due, for the end of a run. */
    val nextDue: Long? get() = scoped?.let { Deck.nextDue(it, progress) }

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

    init {
        tick()
        viewModelScope.launch {
            history.revisions().conflate().collect { reload() }
        }
    }

    private suspend fun reload() {
        val saved = store.read()
        val items = source.load(saved.states.keys)
        val progress = if (items == null) saved else store.update { Deck.prune(it, items.mapTo(HashSet()) { item -> item.id }) }
        mutable.update { it.copy(items = items ?: it.items, progress = progress) }
        tick()
    }

    /** Brings due counts up to date with the clock; Home calls it when shown. */
    fun tick() {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toEpochDay()
        mutable.update { it.copy(now = now, today = today) }
    }

    /** Opens a run of the items due now. Does nothing when none are. */
    fun start() {
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
        var recorded: Pair<String, Grade>? = null
        mutable.update { state ->
            val run = state.run ?: return@update state
            val id = run.current?.id ?: return@update state
            val (next, grade) = run.answer(answer)
            recorded = grade?.let { id to it }
            val progress = grade?.let { Deck.record(state.progress, id, it, state.now, state.today) } ?: state.progress
            state.copy(run = next, progress = progress)
        }
        val (id, grade) = recorded ?: return
        val now = state.value.now
        val today = state.value.today
        viewModelScope.launch { store.update { Deck.record(it, id, grade, now, today) } }
    }

    /** Lab, Language scope: practice only [language]'s items. An open run keeps going. */
    fun setLanguage(language: String?) {
        mutable.update { it.copy(language = language) }
    }

    fun next() {
        mutable.update { state -> state.copy(run = state.run?.next()) }
    }

    /** Closes the run, finished or not; answers so far stay recorded. */
    fun close() {
        mutable.update { it.copy(run = null) }
        tick()
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { PracticeViewModel(app.history, PracticeStore(File(app.filesDir, PracticeStore.FILE))) }
        }
    }
}
