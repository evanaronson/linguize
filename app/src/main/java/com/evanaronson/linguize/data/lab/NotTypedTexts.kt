package com.evanaronson.linguize.data.lab

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Lab, Your words: texts Linguize wrote and the writer then checked, such as a message
 * with its gaps filled or one said in another language. A saved check of one of them holds
 * Linguize's words, not the writer's, so the "check" corpus source leaves it out.
 *
 * Only short hashes are kept (the same 16-hex-character hash History keeps as `textHash`),
 * never the text, in the preferences file `lab_words`, at most [MAX] of them, newest last.
 */
class NotTypedTexts(private val prefs: () -> SharedPreferences) {
    constructor(context: Context) : this({ context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) })

    /** Remembers that [text] was written by Linguize. Call off the main thread. */
    @Synchronized
    fun markNow(text: String) {
        val kept = read().filter { it.isNotEmpty() } - hash(text)
        val next = (kept + hash(text)).takeLast(MAX)
        prefs().edit().putString(KEY, next.joinToString("\n")).apply()
    }

    suspend fun mark(text: String) = withContext(Dispatchers.IO) { markNow(text) }

    /** Hashes of every text marked. */
    suspend fun hashes(): Set<String> = withContext(Dispatchers.IO) { read().filter { it.isNotEmpty() }.toSet() }

    private fun read(): List<String> = prefs().getString(KEY, null)?.split('\n').orEmpty()

    companion object {
        const val FILE = "lab_words"
        private const val KEY = "not_typed"
        const val MAX = 2000

        /** The same hash History keeps as a session's `textHash` (`SessionRecording.hash`). */
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}
