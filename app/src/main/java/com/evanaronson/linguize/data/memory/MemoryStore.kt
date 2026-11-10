package com.evanaronson.linguize.data.memory

import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.data.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID

/**
 * Lab: what Linguize remembers about the writer, and whether it may learn more, in one JSON
 * file of Lab's own (`lab_memory.json`). Nothing here touches History or settings.
 *
 * Reads and writes block, so call them off the main thread. [state] is null until the first
 * [load]. Every change is computed from the current state under one lock and written whole
 * (write, then rename), so a change is all or nothing. IO failures throw without publishing
 * a change. Invalid JSON is preserved as `.unreadable` and recovered with learning disabled.
 */
class MemoryStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val current = MutableStateFlow<MemoryState?>(null)
    private var generation = 0L
    private val forgottenSources = mutableSetOf<String>()

    /** Capture before queueing learning; writer deletion and consent changes invalidate it. */
    internal val learningGeneration: Long get() = synchronized(lock) { generation }

    /** Read the current facts only if this request still belongs to the current consent state. */
    internal fun learningSnapshot(source: String, expectedGeneration: Long): MemoryState? = synchronized(lock) {
        if (!isCurrentLearning(source, expectedGeneration)) null else load().takeIf { it.enabled }
    }

    internal fun isCurrentLearning(source: String, expectedGeneration: Long): Boolean = synchronized(lock) {
        expectedGeneration == generation && source !in forgottenSources
    }

    /** Invalidates queued work immediately, before the caller dispatches the durable cleanup. */
    internal fun invalidateSource(source: String) = synchronized(lock) { forgottenSources += source }

    fun forgetSource(source: String): MemoryState = synchronized(lock) {
        forgottenSources += source
        update { state, _, _ -> state.forgetting(source) }
    }

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
        generation++
        val state = load()
        val fact = state.facts.firstOrNull { it.id == id } ?: return null
        write(state.copy(facts = state.facts - fact))
        fact
    }

    /** Puts a deleted fact back in its place by date. Does nothing if it's already there. */
    fun restore(fact: Fact): Unit = synchronized(lock) {
        val state = load()
        if (state.facts.any { it.id == fact.id }) return
        val sources = fact.sources.filterNot { it in forgottenSources }
        if (!fact.byWriter && fact.sources.isNotEmpty() && sources.isEmpty()) return
        val restored = fact.copy(sources = sources)
        write(state.copy(facts = (state.facts + restored).sortedBy { it.createdAt }))
    }

    /** Forgets every fact and source. Whether learning is on stays as it was. */
    fun clear(): MemoryState = synchronized(lock) {
        // Even an empty clear invalidates an answer that could add the first fact later.
        generation++
        update { state, _, _ -> MemoryState(enabled = state.enabled) }
    }

    fun setEnabled(enabled: Boolean): MemoryState = synchronized(lock) {
        // Enabling again must not make requests queued while disabled eligible to run.
        generation++
        update { state, _, _ -> state.copy(enabled = enabled) }
    }

    private fun read(): MemoryState {
        val contents = try {
            Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            val parent = file.parentFile
            val quarantined = if (parent?.exists() == true) {
                val files = parent.listFiles() ?: throw IOException("Couldn't read memory directory")
                files.any { it.name == file.name + ".unreadable" || it.name.startsWith(file.name + ".unreadable.") }
            } else false
            val empty = MemoryState(enabled = !quarantined)
            // A failed recovery write must not turn an old opt-out into a fresh enabled install.
            if (quarantined) writeAtomically(file, json.encodeToString(MemoryState.serializer(), empty))
            return empty
        }
        return try {
            json.decodeFromString<MemoryState>(contents)
        } catch (e: SerializationException) {
            setAside()
        } catch (e: IllegalArgumentException) {
            setAside()
        }
    }

    private fun setAside(): MemoryState {
        var backup = File(file.parentFile, file.name + ".unreadable")
        var suffix = 1
        while (backup.exists()) backup = File(file.parentFile, file.name + ".unreadable.${suffix++}")
        if (!file.renameTo(backup)) throw IOException("Couldn't preserve unreadable memory")
        val empty = MemoryState(enabled = false)
        writeAtomically(file, json.encodeToString(MemoryState.serializer(), empty))
        return empty
    }

    private fun write(state: MemoryState) {
        writeAtomically(file, json.encodeToString(MemoryState.serializer(), state))
        current.value = state
    }

    companion object {
        const val FILE_NAME = "lab_memory.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
