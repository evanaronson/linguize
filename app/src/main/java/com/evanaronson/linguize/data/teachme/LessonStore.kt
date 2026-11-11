package com.evanaronson.linguize.data.teachme

import android.util.Log
import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.data.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Lessons already made, so a repeat mistake opens its lesson instantly, even offline. */
interface LessonCache {
    /** The lesson for [topic] in [native], or null when none was saved. */
    fun get(topic: LessonTopic, native: String): Lesson?

    fun put(topic: LessonTopic, native: String, lesson: Lesson)
}

/**
 * What identifies one decision's lesson: the target [language], the change ([from] to [to]),
 * the reason [why], and the writer's language [native] the lesson is written in. Spacing is
 * collapsed; languages are compared without case. The change itself keeps its case, which
 * can be the very thing a lesson is about.
 */
@Serializable
data class LessonKey(val language: String, val from: String, val to: String, val why: String, val native: String) {
    /** The same decision, whatever its language: for a card that doesn't know the language. */
    fun sameDecision(other: LessonKey) = from == other.from && to == other.to && why == other.why && native == other.native

    companion object {
        fun of(topic: LessonTopic, native: String, language: String? = topic.language) = LessonKey(
            language = language.orEmpty().tidy().lowercase(),
            from = topic.from.tidy(),
            to = topic.to.tidy(),
            why = topic.why.tidy(),
            native = native.tidy().lowercase(),
        )

        private fun String.tidy() = trim().replace(SPACES, " ")
        private val SPACES = Regex("""\s+""")
    }
}

/**
 * Lab-local lessons in one JSON file ([FILE_NAME] in the app's files), newest last, at most
 * [capacity]. Only the key and the lesson are kept, never the writer's sentence. The file is
 * replaced atomically; one that can't be read counts as empty and is rewritten on the next save.
 */
class LessonStore(private val file: File, private val capacity: Int = DEFAULT_CAPACITY) : LessonCache {
    @Serializable
    internal data class Entry(val key: LessonKey, val lesson: Lesson, val savedAt: Long)

    @Serializable
    internal data class Saved(val version: Int = VERSION, val lessons: List<Entry> = emptyList())

    private var loaded: MutableList<Entry>? = null

    @Synchronized
    override fun get(topic: LessonTopic, native: String): Lesson? {
        val wanted = LessonKey.of(topic, native)
        val entries = entries()
        val match = if (wanted.language.isEmpty()) entries.lastOrNull { it.key.sameDecision(wanted) }
            else entries.lastOrNull { it.key == wanted }
        return match?.lesson
    }

    /** Saved under the language the card knew, and under the one the model named when that differs. */
    @Synchronized
    override fun put(topic: LessonTopic, native: String, lesson: Lesson) {
        val keys = listOf(LessonKey.of(topic, native), LessonKey.of(topic, native, lesson.language))
            .filter { it.language.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(LessonKey.of(topic, native)) }
        val entries = entries()
        entries.removeAll { it.key in keys }
        val now = System.currentTimeMillis()
        keys.forEach { entries += Entry(it, lesson, now) }
        while (entries.size > capacity) entries.removeAt(0)
        write(entries)
    }

    /** How many lessons are saved. */
    @Synchronized
    fun size(): Int = entries().size

    // TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md
    /**
     * Adds the lessons saved in [saved] (another install's file, as text) whose key isn't here
     * yet, oldest first, up to [capacity]: none here is replaced or pushed out. Throws when
     * [saved] or this store's own file can't be read, or the file can't be written.
     */
    @Synchronized
    fun addIfAbsent(saved: String): LessonTransfer {
        val incoming = json.decodeFromString<Saved>(saved).takeIf { it.version == VERSION }?.lessons
            ?: throw IllegalArgumentException("Unknown lessons version")
        // An unreadable file here would read as empty; adding to it would replace it.
        if (file.exists() && json.decodeFromString<Saved>(file.readText()).version != VERSION) {
            throw IllegalStateException("Saved lessons are in another version")
        }
        val entries = entries()
        val known = entries.map { it.key }.toMutableSet()
        var added = 0
        var here = 0
        var full = 0
        val merged = entries.toMutableList()
        for (entry in incoming.sortedBy { it.savedAt }) {
            when {
                entry.key in known -> here++
                merged.size >= capacity -> full++
                else -> { merged += entry; known += entry.key; added++ }
            }
        }
        if (added > 0) {
            writeAtomically(file, json.encodeToString(Saved.serializer(), Saved(lessons = merged)))
            loaded = merged
        }
        return LessonTransfer(added, here, full)
    }

    // TEMPORARY: lab transfer bridge. What [addIfAbsent] did.
    data class LessonTransfer(val added: Int, val here: Int, val overCapacity: Int)

    private fun entries(): MutableList<Entry> = loaded ?: read().toMutableList().also { loaded = it }

    private fun read(): List<Entry> = try {
        if (!file.exists()) emptyList()
        else json.decodeFromString<Saved>(file.readText()).takeIf { it.version == VERSION }?.lessons.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read saved lessons; starting empty", e)
        emptyList()
    }

    private fun write(entries: List<Entry>) {
        try {
            writeAtomically(file, json.encodeToString(Saved.serializer(), Saved(lessons = entries)))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save lessons", e)
        }
    }

    companion object {
        /** In the app's private files; nothing else reads or writes it. */
        const val FILE_NAME = "lab_teach_me.json"
        const val DEFAULT_CAPACITY = 200
        private const val VERSION = 1
        private const val TAG = "LessonStore"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
