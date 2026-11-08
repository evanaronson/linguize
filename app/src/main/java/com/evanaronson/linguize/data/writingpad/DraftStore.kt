package com.evanaronson.linguize.data.writingpad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** One Writing pad draft. [language] is a language code, or null for auto-detect. */
@Serializable
data class Draft(
    val id: String,
    val title: String = "",
    val text: String = "",
    val language: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isEmpty get() = title.isBlank() && text.isBlank()

    /** The title the list shows: the writer's own, else the text's first line. */
    fun displayTitle(maxChars: Int = 60): String? {
        val own = title.trim()
        if (own.isNotEmpty()) return own
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length <= maxChars) line else line.take(maxChars).trimEnd() + "…"
    }
}

/**
 * Writing pad drafts, in one JSON file of their own ([file], named `lab_writing_pad.json`), so
 * they never touch History or settings. Writes go to a temporary file first and replace the old
 * one in a single rename, so a crash mid-write keeps the previous drafts. Calls are serialized
 * and blocking: make them off the main thread.
 */
class DraftStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Contents(val version: Int = 1, val drafts: List<Draft> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cache: List<Draft>? = null

    /** Every draft, most recently changed first. An unreadable file reads as no drafts. */
    @Synchronized
    fun all(): List<Draft> = load().sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(id: String): Draft? = load().firstOrNull { it.id == id }

    /** A new empty draft, saved. */
    @Synchronized
    fun create(language: String? = null): Draft {
        val now = clock()
        val draft = Draft(UUID.randomUUID().toString(), language = language, createdAt = now, updatedAt = now)
        write(load() + draft)
        return draft
    }

    /** Saves [title], [text] and [language] to draft [id]; returns the saved draft, or null when it is gone. */
    @Synchronized
    fun save(id: String, title: String, text: String, language: String?): Draft? {
        val drafts = load()
        val old = drafts.firstOrNull { it.id == id } ?: return null
        if (old.title == title && old.text == text && old.language == language) return old
        val saved = old.copy(title = title, text = text, language = language, updatedAt = clock())
        write(drafts.map { if (it.id == id) saved else it })
        return saved
    }

    @Synchronized
    fun delete(id: String) {
        val drafts = load()
        if (drafts.none { it.id == id }) return
        write(drafts.filterNot { it.id == id })
    }

    /** Puts back [draft] as it was, as when a deletion is undone. */
    @Synchronized
    fun restore(draft: Draft) {
        write(load().filterNot { it.id == draft.id } + draft)
    }

    private fun load(): List<Draft> = cache ?: read().also { cache = it }

    private fun read(): List<Draft> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(Contents.serializer(), file.readText()).drafts
        } catch (_: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it on the next save.
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            emptyList()
        }
    }

    private fun write(drafts: List<Draft>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString(Contents.serializer(), Contents(drafts = drafts)))
            if (!temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
            cache = drafts
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    companion object {
        const val FILE_NAME = "lab_writing_pad.json"
    }
}
