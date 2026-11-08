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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** The note that was just deleted, while its Undo can still bring it back. */
    val lastDeleted: StateFlow<Fact?> = deleted.asStateFlow()

    /** Null until the notes have been read. */
    val state: StateFlow<MemoryScreenState?> = store.state.map { it?.screen() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch(io) { store.load() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch(io) { store.setEnabled(enabled) }
    }

    /** Saves the writer's own note; [onDone] hears whether it was saved. */
    fun add(text: String, category: FactCategory, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onDone(withContext(io) { store.add(text, category) } != null)
        }
    }

    fun edit(id: String, text: String, category: FactCategory, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onDone(withContext(io) { store.edit(id, text, category) })
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(io) { store.delete(id) }?.let { deleted.value = it }
        }
    }

    fun undoDelete() {
        val fact = deleted.value ?: return
        deleted.value = null
        viewModelScope.launch(io) { store.restore(fact) }
    }

    /** The Undo moment for [fact] has passed. */
    fun deleteConfirmed(fact: Fact) {
        if (deleted.value == fact) deleted.value = null
    }

    fun forgetEverything() {
        deleted.value = null
        viewModelScope.launch(io) { store.clear() }
    }

    companion object {
        fun factory(store: MemoryStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoryViewModel(store) }
        }
    }
}

private fun MemoryState.screen() = MemoryScreenState(enabled, facts.size, groupFacts(facts), sources)
