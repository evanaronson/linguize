package com.evanaronson.linguize.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.data.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Notes of one category, oldest first. */
data class FactGroup(val category: FactCategory, val facts: List<Fact>)

/** What "What Linguize knows" shows. */
data class MemoryScreenState(
    val enabled: Boolean,
    val total: Int,
    val groups: List<FactGroup>,
    /** When each source was first learned from, for captions. */
    val sources: Map<String, Long> = emptyMap(),
)

/** Groups in the categories' order, empty ones left out, oldest first within each. */
fun groupFacts(facts: List<Fact>): List<FactGroup> = FactCategory.entries.mapNotNull { category ->
    facts.filter { it.category == category }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
        .takeIf { it.isNotEmpty() }
        ?.let { FactGroup(category, it) }
}

/** Where a note came from, for its caption. Dates are epoch milliseconds. */
sealed interface FactOrigin {
    /** Typed in by the writer, or edited by them after every source was deleted. */
    data class Writer(val edited: Boolean) : FactOrigin

    /** Learned from one source; [chat] when it was a conversation. */
    data class One(val chat: Boolean, val at: Long, val edited: Boolean) : FactOrigin

    /** Learned from [count] chats, the latest on [latest]. */
    data class Many(val count: Int, val latest: Long, val edited: Boolean) : FactOrigin
}

/** [fact]'s origin, with source dates from [sources] and the fact's own dates as a fallback. */
fun originOf(fact: Fact, sources: Map<String, Long>): FactOrigin {
    if (fact.sources.isEmpty()) return FactOrigin.Writer(edited = fact.updatedAt != fact.createdAt)
    val dates = fact.sources.map { sources[it] ?: fact.createdAt }
    return if (fact.sources.size == 1) {
        FactOrigin.One(fact.sources.single().startsWith(CHAT_PREFIX), dates.single(), fact.byWriter)
    } else {
        FactOrigin.Many(fact.sources.size, dates.max(), fact.byWriter)
    }
}

private const val CHAT_PREFIX = "chat:"

enum class MemoryFailure { Load, Save, Editor }

/**
 * The screen's state and actions: switch learning, add, edit, delete with Undo, forget
 * everything. A deleted note is deleted at once and kept here until Undo or the next
 * deletion.
 */
class MemoryViewModel internal constructor(
    private val store: MemoryStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val deleted = MutableStateFlow<Fact?>(null)
    private val commands = Mutex()
    private val _failure = MutableStateFlow<MemoryFailure?>(null)
    val failure: StateFlow<MemoryFailure?> = _failure.asStateFlow()
    private var retryAction: (() -> Unit)? = null
    private val pendingRestores = linkedMapOf<String, Fact>()
    private val _undoFailed = MutableStateFlow(false)
    val undoFailed: StateFlow<Boolean> = _undoFailed.asStateFlow()

    /** The note that was just deleted, while its Undo can still bring it back. */
    val lastDeleted: StateFlow<Fact?> = deleted.asStateFlow()

    /** Null until the notes have been read. */
    val state: StateFlow<MemoryScreenState?> = store.state.map { it?.screen() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        perform(MemoryFailure.Load, action = { store.load() })
    }

    fun setEnabled(enabled: Boolean) {
        perform(action = { store.setEnabled(enabled) })
    }

    /** Saves the writer's own note; [onDone] hears whether it was saved. */
    fun add(text: String, category: FactCategory, onDone: (Boolean) -> Unit = {}) {
        perform(MemoryFailure.Editor, action = { store.add(text, category) }, onDone = { onDone(it != null) }, onFailure = { onDone(false) })
    }

    fun edit(id: String, text: String, category: FactCategory, onDone: (Boolean) -> Unit = {}) {
        perform(MemoryFailure.Editor, action = { store.edit(id, text, category) }, onDone = onDone, onFailure = { onDone(false) })
    }

    fun delete(id: String) {
        perform(action = { store.delete(id) }, onDone = { fact -> fact?.let { deleted.value = it } })
    }

    fun undoDelete() {
        val fact = deleted.value ?: return
        restore(fact)
    }

    private fun restore(fact: Fact) {
        pendingRestores[fact.id] = fact
        perform(failure = null, action = { store.restore(fact) }, onDone = {
            pendingRestores.remove(fact.id)
            _undoFailed.value = pendingRestores.isNotEmpty()
            if (deleted.value == fact) deleted.value = null
        }, onFailure = { _undoFailed.value = true }, isCurrent = { pendingRestores[fact.id] == fact })
    }

    fun retryUndo() { pendingRestores.values.toList().forEach(::restore) }

    /** The Undo moment for [fact] has passed. */
    fun deleteConfirmed(fact: Fact) {
        if (deleted.value == fact) deleted.value = null
    }

    fun forgetEverything(onDone: () -> Unit = {}) {
        perform(action = { store.clear() }, onDone = {
            deleted.value = null
            pendingRestores.clear()
            _undoFailed.value = false
            onDone()
        })
    }

    fun retry() { retryAction?.invoke() }

    /** Commands stay ordered; only a durable result updates the editor, switch, or Undo. */
    private fun <T> perform(
        failure: MemoryFailure? = MemoryFailure.Save,
        action: () -> T,
        onDone: (T) -> Unit = {},
        onFailure: () -> Unit = {},
        isCurrent: () -> Boolean = { true },
    ) {
        viewModelScope.launch {
            commands.withLock {
                if (!isCurrent()) return@withLock
                if (failure != null) {
                    _failure.value = null
                    retryAction = null
                }
                try {
                    val result = withContext(io) { action() }
                    onDone(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    if (failure != null) {
                        // An editor must resubmit its current text; a captured retry could save stale input.
                        retryAction = if (failure == MemoryFailure.Editor) null else {
                            { perform(failure, action, onDone, onFailure, isCurrent) }
                        }
                        _failure.value = failure
                    }
                    onFailure()
                }
            }
        }
    }

    companion object {
        fun factory(store: MemoryStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoryViewModel(store) }
        }
    }
}

private fun MemoryState.screen() = MemoryScreenState(enabled, facts.size, groupFacts(facts), sources)
