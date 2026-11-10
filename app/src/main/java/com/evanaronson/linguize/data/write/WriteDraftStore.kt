package com.evanaronson.linguize.data.write

import com.evanaronson.linguize.data.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * One Write draft. [language] is a language code, or null for auto-detect. [title] is only
 * set on drafts brought over from the Writing pad, which had titles; the list shows it.
 */
@Serializable
data class WriteDraft(
    val id: String,
    val text: String = "",
    val language: String? = null,
    val title: String = "",
    val updatedAt: Long,
) {
    val isBlank get() = text.isBlank()

    /** What the drafts list shows: the title, else the first line, shortened. */
    fun label(maxChars: Int = 60): String {
        val line = title.trim().ifEmpty {
            text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        return if (line.length <= maxChars) line else line.take(maxChars).trimEnd() + "…"
    }
}

/** The drafts, most recently changed first, and the one open in the editor (null for none yet). */
data class WriteDrafts(val currentId: String? = null, val drafts: List<WriteDraft> = emptyList()) {
    val current: WriteDraft? get() = drafts.firstOrNull { it.id == currentId }

    /** Every draft but the open one: the Drafts list. */
    val others: List<WriteDraft> get() = drafts.filter { it.id != currentId }
}

/**
 * Write's drafts, in one JSON file of their own ([file], `lab_write.json`), so they never touch
 * History or settings. Writes go to a temporary file and replace the old one in a single rename.
 * The first read imports the Writing pad's drafts from [legacy] (`lab_writing_pad.json`) when
 * [file] doesn't exist yet; [legacy] is only read, never changed. Calls are serialized and
 * blocking: make them off the main thread.
 */
class WriteDraftStore(
    private val file: File,
    private val legacy: File? = null,
) {
    data class Selection(val draft: WriteDraft?, val empty: Boolean, val deletionVersion: Int)
    @Serializable
    private data class Contents(val version: Int = 1, val currentId: String? = null, val drafts: List<WriteDraft> = emptyList())

    /** The Writing pad's file, as it wrote it. */
    @Serializable
    private data class PadDraft(
        val id: String,
        val title: String = "",
        val text: String = "",
        val language: String? = null,
        val updatedAt: Long = 0,
    )

    @Serializable
    private data class PadContents(val drafts: List<PadDraft> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cache: Contents? = null
    private val selection = AtomicLong()
    private val edits = AtomicLong()
    private val latestEdits = ConcurrentHashMap<String, Long>()
    private val deletions = ConcurrentHashMap<String, Int>()

    /** Reserves selection without IO; delayed saves from older editors still keep their text. */
    fun claimSelection(): Long = selection.incrementAndGet()

    /** Input order is reserved on Main, before a save can be delayed behind IO. */
    fun noteEdited(id: String?): Long = edits.incrementAndGet().also { revision ->
        if (id != null) latestEdits.merge(id, revision) { previous, next -> maxOf(previous, next) }
    }

    fun deletionVersion(id: String?): Int = id?.let { deletions[it] } ?: 0

    @Synchronized
    fun load(): WriteDrafts = contents().let { c -> WriteDrafts(c.currentId, c.drafts.sortedByDescending { it.updatedAt }) }

    /** The chosen contents and their deletion version are one synchronized read. */
    @Synchronized
    fun loadSelection(id: String?): Selection {
        val loaded = load()
        val draft = if (id != null) loaded.drafts.firstOrNull { it.id == id }
            else loaded.current ?: loaded.drafts.firstOrNull()
        return Selection(draft, loaded.drafts.isEmpty(), deletionVersion(draft?.id ?: id))
    }

    /** Adds or replaces [draft] and makes it the open one. */
    @Synchronized
    fun saveCurrent(draft: WriteDraft) {
        val c = contents()
        write(Contents(currentId = draft.id, drafts = c.drafts.filterNot { it.id == draft.id } + draft))
    }

    /** Saves text without letting an older editor replace the most recently selected note. */
    @Synchronized
    fun save(draft: WriteDraft, selectedBy: Long?, revision: Long, expectedDeletion: Int): Boolean {
        if (deletionVersion(draft.id) != expectedDeletion || !latest(draft.id, revision)) return false
        val c = contents()
        val next = if (draft.isBlank) {
            // Emptying an editor removes its row, but typing in that same editor may save it again.
            c.copy(currentId = c.currentId.takeIf { it != draft.id }, drafts = c.drafts.filterNot { it.id == draft.id })
        } else {
            val current = if (selectedBy != null && selection.get() == selectedBy) draft.id else c.currentId
            c.copy(currentId = current, drafts = c.drafts.filterNot { it.id == draft.id } + draft)
        }
        if (next != c) write(next)
        return true
    }

    /** Opens draft [id], or nothing (a new blank draft not saved yet) for null. */
    @Synchronized
    fun setCurrent(id: String?, selectedBy: Long? = null) {
        if (selectedBy != null && selection.get() != selectedBy) return
        val c = contents()
        if (c.currentId == id) return
        write(c.copy(currentId = id?.takeIf { wanted -> c.drafts.any { it.id == wanted } }))
    }

    @Synchronized
    fun delete(id: String): Int {
        val c = contents()
        if (c.drafts.any { it.id == id }) {
            write(c.copy(currentId = c.currentId.takeIf { it != id }, drafts = c.drafts.filterNot { it.id == id }))
        }
        return deletions.merge(id, 1) { before, increment -> before + increment }!!
    }

    private fun latest(id: String, revision: Long): Boolean =
        latestEdits.merge(id, revision) { previous, next -> maxOf(previous, next) } == revision

    /** Puts back [draft] as it was, as when a deletion is undone; the open draft stays open. */
    @Synchronized
    fun restore(draft: WriteDraft, expectedDeletion: Int? = null): Boolean {
        if (expectedDeletion != null && deletionVersion(draft.id) != expectedDeletion) return false
        val c = contents()
        // A failed Undo may be retried after the same note has been opened and edited again.
        if (c.drafts.any { it.id == draft.id }) return true
        write(c.copy(drafts = c.drafts.filterNot { it.id == draft.id } + draft))
        return true
    }

    private fun contents(): Contents = cache ?: read().also { cache = it }

    private fun read(): Contents {
        val text = readText(file) ?: return imported()
        return try {
            json.decodeFromString(Contents.serializer(), text)
        } catch (_: SerializationException) {
            setAside()
        } catch (_: IllegalArgumentException) {
            setAside()
        }
    }

    private fun setAside(): Contents {
        // A previous corrupt file is evidence too. Never overwrite it or publish an empty
        // store until this file has actually been preserved.
        var backup = File(file.parentFile, file.name + ".unreadable")
        var suffix = 1
        while (backup.exists()) backup = File(file.parentFile, file.name + ".unreadable.${suffix++}")
        if (!file.renameTo(backup)) throw IOException("Couldn't preserve unreadable notes")
        return Contents()
    }

    private fun readText(source: File): String? = try {
        String(Files.readAllBytes(source.toPath()), Charsets.UTF_8)
    } catch (_: NoSuchFileException) {
        null
    }

    /** The Writing pad's non-blank drafts, the newest open. Saved at once so this runs only once. */
    private fun imported(): Contents {
        val text = legacy?.let(::readText) ?: return Contents()
        val pad = try {
            json.decodeFromString(PadContents.serializer(), text)
        } catch (_: SerializationException) {
            return Contents()
        } catch (_: IllegalArgumentException) {
            return Contents()
        }
        val drafts = pad.drafts.filter { it.text.isNotBlank() }
            .map { WriteDraft(it.id, it.text, it.language, it.title.trim(), it.updatedAt) }
        if (drafts.isEmpty()) return Contents()
        val contents = Contents(currentId = drafts.maxBy { it.updatedAt }.id, drafts = drafts)
        // On failure the legacy file remains the source, and a retry imports it again.
        write(contents)
        return contents
    }

    private fun write(contents: Contents) {
        writeAtomically(file, json.encodeToString(Contents.serializer(), contents))
        cache = contents
    }

    companion object {
        const val FILE_NAME = "lab_write.json"

        /** The Writing pad's drafts, which Write imports once. */
        const val PAD_FILE_NAME = "lab_writing_pad.json"
    }
}
