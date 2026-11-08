package com.evanaronson.linguize.data.memory

import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Lab: what Linguize remembers about the writer, and whether it may learn more, in one JSON
 * file of Lab's own (`lab_memory.json`). Nothing here touches History or settings.
 *
 * Reads and writes block, so call them off the main thread. [state] is null until the first
 * [load]. Every change is computed from the current state under one lock and written whole
 * (write, then rename), so a change is all or nothing. A file this build can't read is set
 * aside as `.unreadable`, never deleted.
 */
class MemoryStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val current = MutableStateFlow<MemoryState?>(null)

    /** Everything remembered; null until loaded. */
    val state: StateFlow<MemoryState?> = current.asStateFlow()

    /** Reads the file once; later calls return what's in memory. */
    fun load(): MemoryState = synchronized(lock) {
        current.value ?: read().also { current.value = it }
    }

    /** Replaces the state with [change] of it, in one write. Returns the new state. */
    fun update(change: (MemoryState, now: Long, newId: () -> String) -> MemoryState): MemoryState = synchronized(lock) {
        val before = load()
        val after = change(before, clock(), newId)
        if (after != before) write(after)
        after
    }

    /** The writer's own note, or null when [text] isn't one. */
    fun add(text: String, category: FactCategory): Fact? = synchronized(lock) {
        val (after, fact) = load().adding(text, category, clock(), newId) ?: return null
        write(after)
        fact
    }

    /** The writer's edit of fact [id]; false when the text isn't a note or the fact is gone. */
    fun edit(id: String, text: String, category: FactCategory): Boolean = synchronized(lock) {
        val after = load().editing(id, text, category, clock()) ?: return false
        write(after)
        true
    }

    /** Deletes fact [id]; returns it, for Undo, or null when there was none. */
    fun delete(id: String): Fact? = synchronized(lock) {
        val state = load()
        val fact = state.facts.firstOrNull { it.id == id } ?: return null
        write(state.copy(facts = state.facts - fact))
        fact
    }

    /** Puts a deleted fact back in its place by date. Does nothing if it's already there. */
    fun restore(fact: Fact) = synchronized(lock) {
        val state = load()
        if (state.facts.any { it.id == fact.id }) return
        write(state.copy(facts = (state.facts + fact).sortedBy { it.createdAt }))
    }

    /** Forgets every fact and source. Whether learning is on stays as it was. */
    fun clear() = update { state, _, _ -> MemoryState(enabled = state.enabled) }

    fun setEnabled(enabled: Boolean) = update { state, _, _ -> state.copy(enabled = enabled) }

    private fun read(): MemoryState {
        if (!file.exists()) return MemoryState()
        return try {
            json.decodeFromString<MemoryState>(file.readText())
        } catch (e: SerializationException) {
            setAside()
        } catch (e: IllegalArgumentException) {
            setAside()
        } catch (e: IOException) {
            MemoryState()
        }
    }

    private fun setAside(): MemoryState {
        file.renameTo(File(file.parentFile, file.name + ".unreadable"))
        return MemoryState()
    }

    private fun write(state: MemoryState) {
        current.value = state
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(MemoryState.serializer(), state))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: IOException) {
            // Kept in memory for this process; the next change tries the file again.
        }
    }

    companion object {
        const val FILE_NAME = "lab_memory.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
