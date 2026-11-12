package com.evanaronson.linguize.data.language

import android.content.Context
import android.content.SharedPreferences
import com.evanaronson.linguize.core.language.MyLanguages
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The writer's languages, as the app reads and edits them. An interface so view models test without Android. */
interface LanguageList {
    /** The writer's languages, in order; never empty. */
    val codes: List<String>

    /** Adds [code] at the end (any ISO 639-1 code); returns the new list. */
    fun add(code: String): List<String>

    /** Removes [code], unless it's the last; returns the new list. */
    fun remove(code: String): List<String>

    /** Moves [code] to [index]; returns the new list. */
    fun move(code: String, index: Int): List<String>
}

/**
 * Lab, language-generic: the writer's languages ([MyLanguages]) in Lab's own preferences file
 * `lab_lang_generic`. Until the writer edits the list, nothing is stored and it reads as
 * [seed]: the languages their writing profiles name (Catalan and Spanish for everyone so far).
 * The first edit stores the whole list. Every instance reads the same file, so any number may exist.
 */
class MyLanguagesStore(
    context: Context,
    /** The languages the writing profiles name, in order, for the list before it's edited. */
    private val seed: () -> List<String?>,
) : LanguageList {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val codes: List<String>
        get() = MyLanguages.read(runCatching { prefs.getString(LANGUAGES, null) }.getOrNull())
            ?: MyLanguages.seed(runCatching(seed).getOrDefault(emptyList()))

    override fun add(code: String): List<String> = update { MyLanguages.add(it, code) }

    override fun remove(code: String): List<String> = update { MyLanguages.remove(it, code) }

    override fun move(code: String, index: Int): List<String> = update { MyLanguages.move(it, code, index) }

    private fun update(change: (List<String>) -> List<String>): List<String> = synchronized(lock) {
        val before = codes
        val after = change(before)
        if (after != before) prefs.edit().putString(LANGUAGES, MyLanguages.write(after)).apply()
        after
    }

    /** The list now and after every change, from any instance. */
    fun changes(): Flow<List<String>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LANGUAGES) trySend(codes)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(codes)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        /** Lab's own preferences file; nothing else is kept in it. */
        const val FILE = "lab_lang_generic"
        private const val LANGUAGES = "languages"
        private val lock = Any()
    }
}
