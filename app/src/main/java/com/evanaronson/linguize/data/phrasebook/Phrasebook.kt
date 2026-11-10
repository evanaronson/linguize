package com.evanaronson.linguize.data.phrasebook

import android.content.Context
import android.util.Log
import com.evanaronson.linguize.data.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.Locale
import java.util.UUID

/** Where a saved phrase came from. */
@Serializable
enum class PhraseSource {
    /** A fix on a check card or in a saved check. */
    @SerialName("fix") Fix,

    /** A rewording on a check card or in a saved check. */
    @SerialName("rewording") Rewording,

    /** Typed in on the Phrasebook screen. */
    @SerialName("manual") Manual,
}

/** One saved phrase: the words worth keeping, what they replaced, and why, in the writer's own language. */
@Serializable
data class Phrase(
    val id: String,
    /** The natural or correct wording. */
    val phrase: String,
    /** What the writer had written instead; null when there was nothing (or it was typed in without one). */
    val replaced: String? = null,
    /** The reason or meaning, in the writer's own language, as the check gave it or as typed. */
    val meaning: String? = null,
    /** ISO 639-1 code of the phrase's language; null when it wasn't known. */
    val language: String? = null,
    val createdAt: Long,
    val source: PhraseSource = PhraseSource.Manual,
    /** Package name of the app the checked text came from, when known. */
    val app: String? = null,
    /** The saved check it was taken from, when it came from History. */
    val sessionId: String? = null,
) {
    /** Whether this is the same phrase as [draft]: same words, same replaced words, same language. */
    fun matches(draft: PhraseDraft): Boolean = key == draft.key

    internal val key get() = PhraseKey(phrase.normalizedKey(), replaced?.normalizedKey().orEmpty(), language)
}

/** A phrase about to be saved. Text is tidied (trimmed, single spaces) on save. */
data class PhraseDraft(
    val phrase: String,
    val replaced: String? = null,
    val meaning: String? = null,
    val language: String? = null,
    val source: PhraseSource = PhraseSource.Manual,
    val app: String? = null,
    val sessionId: String? = null,
) {
    internal val key get() = PhraseKey(phrase.normalizedKey(), replaced?.normalizedKey().orEmpty(), language)

    /** True when there is something to save. */
    val isValid get() = phrase.tidy().isNotEmpty() && phrase.tidy().length <= PhrasebookStore.MAX_PHRASE
}

internal data class PhraseKey(val phrase: String, val replaced: String, val language: String?)

/** Single spaces, no edge whitespace; line breaks inside a phrase become spaces. */
internal fun String.tidy(): String = trim().replace(Regex("\\s+"), " ")

private fun String.normalizedKey(): String = tidy().lowercase(Locale.ROOT)

private fun String?.tidyOrNull(): String? = this?.tidy()?.takeIf { it.isNotEmpty() }

@Serializable
private data class PhrasebookFile(val version: Int = 1, val phrases: List<Phrase> = emptyList())

/**
 * The writer's saved phrases, newest first, in one JSON file of Lab's own
 * (`lab_phrasebook.json`). Nothing here touches History or settings: deleting a saved check
 * leaves its phrases, and deleting a phrase leaves the check.
 *
 * Reads and writes block, so call them off the main thread. [phrases] is null until the
 * first [load]; every change replaces the file atomically (write, then rename). A file
 * this build can't read is set aside as `.unreadable`, never deleted.
 */
class PhrasebookStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val state = MutableStateFlow<List<Phrase>?>(null)

    /** Every saved phrase, newest first; null until loaded. */
    val phrases: StateFlow<List<Phrase>?> = state.asStateFlow()

    /** Reads the file once; later calls return committed state. Read failures are retryable. */
    fun load(): List<Phrase> = synchronized(lock) {
        state.value ?: read().also { state.value = it }
    }

    /** The saved phrase that [draft] would duplicate, if any. */
    fun find(draft: PhraseDraft): Phrase? = load().firstOrNull { it.matches(draft) }

    /**
     * Saves [draft], or returns the phrase already saved with the same words, replaced
     * words and language (filling in a meaning it lacked). Null when the draft is empty.
     */
    fun save(draft: PhraseDraft): Phrase? = synchronized(lock) {
        if (!draft.isValid) return null
        val current = load()
        current.firstOrNull { it.matches(draft) }?.let { existing ->
            val meaning = draft.meaning.tidyOrNull()
            if (existing.meaning == null && meaning != null) {
                val filled = existing.copy(meaning = meaning)
                write(current.map { if (it.id == existing.id) filled else it })
                return filled
            }
            return existing
        }
        val phrase = Phrase(
            id = newId(),
            phrase = draft.phrase.tidy(),
            replaced = draft.replaced.tidyOrNull(),
            meaning = draft.meaning.tidyOrNull(),
            language = draft.language,
            createdAt = clock(),
            source = draft.source,
            app = draft.app,
            sessionId = draft.sessionId,
        )
        write(listOf(phrase) + current)
        phrase
    }

    /** Deletes the phrase with [id]; returns it, for Undo, or null when there was none. */
    fun delete(id: String): Phrase? = synchronized(lock) {
        val current = load()
        val removed = current.firstOrNull { it.id == id } ?: return null
        write(current.filterNot { it.id == id })
        removed
    }

    /** Puts a deleted phrase back where its date places it. Does nothing if it's already there. */
    fun restore(phrase: Phrase): Unit = synchronized(lock) {
        val current = load()
        if (current.any { it.id == phrase.id || it.key == phrase.key }) return
        write((current + phrase).sortedByDescending { it.createdAt })
    }

    private fun read(): List<Phrase> {
        val text = try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        return try {
            json.decodeFromString<PhrasebookFile>(text).phrases.sortedByDescending { it.createdAt }
        } catch (e: SerializationException) {
            setAside(e)
        } catch (e: IllegalArgumentException) {
            setAside(e)
        }
    }

    private fun setAside(e: Exception): List<Phrase> {
        val backup = File(file.parentFile, file.name + ".unreadable")
        if (backup.exists() || !file.renameTo(backup)) {
            throw IOException("Couldn't preserve the unreadable phrasebook", e)
        }
        Log.w(TAG, "Phrasebook file unreadable; set aside", e)
        return emptyList()
    }

    private fun write(phrases: List<Phrase>) {
        writeAtomically(file, json.encodeToString(PhrasebookFile.serializer(), PhrasebookFile(phrases = phrases)))
        state.value = phrases
    }

    companion object {
        const val FILE_NAME = "lab_phrasebook.json"
        const val MAX_PHRASE = 300
        private const val TAG = "Phrasebook"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        @Volatile private var shared: PhrasebookStore? = null

        /** The app's one phrasebook. Cheap: nothing is read until [load]. */
        fun of(context: Context): PhrasebookStore = shared ?: synchronized(this) {
            shared ?: PhrasebookStore(File(context.applicationContext.filesDir, FILE_NAME)).also { shared = it }
        }
    }
}
