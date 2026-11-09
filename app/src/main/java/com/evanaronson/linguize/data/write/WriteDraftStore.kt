package com.evanaronson.linguize.data.write

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

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

    @Synchronized
    fun load(): WriteDrafts = contents().let { c -> WriteDrafts(c.currentId, c.drafts.sortedByDescending { it.updatedAt }) }

    /** Adds or replaces [draft] and makes it the open one. */
    @Synchronized
    fun saveCurrent(draft: WriteDraft) {
        val c = contents()
        write(Contents(currentId = draft.id, drafts = c.drafts.filterNot { it.id == draft.id } + draft))
    }

    /** Opens draft [id], or nothing (a new blank draft not saved yet) for null. */
    @Synchronized
    fun setCurrent(id: String?) {
        val c = contents()
        if (c.currentId == id) return
        write(c.copy(currentId = id?.takeIf { wanted -> c.drafts.any { it.id == wanted } }))
    }

    @Synchronized
    fun delete(id: String) {
        val c = contents()
        if (c.drafts.none { it.id == id }) return
        write(c.copy(currentId = c.currentId.takeIf { it != id }, drafts = c.drafts.filterNot { it.id == id }))
    }

    /** Puts back [draft] as it was, as when a deletion is undone; the open draft stays open. */
    @Synchronized
    fun restore(draft: WriteDraft) {
        val c = contents()
        write(c.copy(drafts = c.drafts.filterNot { it.id == draft.id } + draft))
    }

    private fun contents(): Contents = cache ?: read().also { cache = it }

    private fun read(): Contents {
        if (!file.exists()) return imported()
        return try {
            json.decodeFromString(Contents.serializer(), file.readText())
        } catch (_: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it on the next save.
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            Contents()
        }
    }

    /** The Writing pad's non-blank drafts, the newest open. Saved at once so this runs only once. */
    private fun imported(): Contents {
        val source = legacy?.takeIf { it.exists() } ?: return Contents()
        val pad = try {
            json.decodeFromString(PadContents.serializer(), source.readText())
        } catch (_: Exception) {
            return Contents()
        }
        val drafts = pad.drafts.filter { it.text.isNotBlank() }
            .map { WriteDraft(it.id, it.text, it.language, it.title.trim(), it.updatedAt) }
        if (drafts.isEmpty()) return Contents()
        val contents = Contents(currentId = drafts.maxBy { it.updatedAt }.id, drafts = drafts)
        try {
            write(contents)
        } catch (_: IOException) {
            // Imported again next time; the pad's file is untouched either way.
        }
        return contents
    }

    private fun write(contents: Contents) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString(Contents.serializer(), contents))
            if (!temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
            cache = contents
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    companion object {
        const val FILE_NAME = "lab_write.json"

        /** The Writing pad's drafts, which Write imports once. */
        const val PAD_FILE_NAME = "lab_writing_pad.json"
    }
}
