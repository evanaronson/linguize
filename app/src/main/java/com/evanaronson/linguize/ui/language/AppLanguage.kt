package com.evanaronson.linguize.ui.language

import android.content.Context
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.data.language.LanguageChoice
import com.evanaronson.linguize.data.language.LanguageList
import com.evanaronson.linguize.data.language.LanguageScopePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One language on the selector, with the variety the writer's profile names (for its flag). */
data class LanguageOption(val code: String, val variety: String? = null)

/**
 * Lab, Language scope: the writer's current language, shared by the whole app and set by the
 * one selector in the top bar of the four tabs. Null is **Linguize** (Auto), the default:
 * Chat lists every conversation, a new one starts in the chat's start language, Notes
 * detects, Progress shows one block per language in use and History shows everything. A
 * language (any ISO 639-1 code: one of the writer's languages, [mine]) scopes all of them to
 * it. A switch is kept ([LanguageChoice]); a language stored before Auto existed is kept as it is.
 *
 * The selector's short list is Auto + the writer's languages ([options]); languages found in
 * History that the writer hasn't added are [suggested], for an "Add languages" screen.
 *
 * Checks from other apps (selection menu, accessibility button, tile, share) aren't scoped:
 * they detect the language, or use the Catalanize or Castilianize menu entry, as before.
 */
class AppLanguage internal constructor(
    private val choice: LanguageChoice,
    /** The writer's writing profiles, in order: each one's variety, for Castilianize's flag. */
    private val profiles: suspend () -> List<WritingProfile>,
    /** Languages found in their History, most used first. */
    private val dataLanguages: suspend () -> List<String>,
    /** The writer's languages (Lab, language-generic). */
    private val languages: LanguageList = DefaultLanguages,
) {
    private var profileCache: List<WritingProfile> = emptyList()
    private var dataCache: List<String> = emptyList()
    private val mutableCurrent = MutableStateFlow(LanguageScope.current(choice.current))
    private val mutableMine = MutableStateFlow(LanguageScope.DEFAULT_LANGUAGES)
    private val mutableOptions = MutableStateFlow(optionsFor())
    private val mutableSuggested = MutableStateFlow(emptyList<String>())

    /** The current language; null is Linguize (Auto). Known at once, from what was stored. */
    val current: StateFlow<String?> = mutableCurrent.asStateFlow()

    /**
     * The languages the selector lists after Linguize: the writer's languages in their order,
     * then the current language if it isn't one of them.
     */
    val options: StateFlow<List<LanguageOption>> = mutableOptions.asStateFlow()

    /** The writer's languages, in order (Lab, language-generic). */
    val mine: StateFlow<List<String>> = mutableMine.asStateFlow()

    /** Languages found in History the writer hasn't added, most used first. */
    val suggested: StateFlow<List<String>> = mutableSuggested.asStateFlow()

    /** Reads the writer's languages, the profiles' varieties and the languages found in History. Safe to call again. */
    suspend fun load() {
        profileCache = runCatching { profiles() }.getOrDefault(emptyList())
        dataCache = runCatching { dataLanguages() }.getOrDefault(emptyList())
        val codes = withContext(Dispatchers.IO) { runCatching { languages.codes }.getOrNull() }
        if (!codes.isNullOrEmpty()) mutableMine.value = codes
        refreshOptions()
    }

    /** The writer's languages changed (here or on another screen): the short list follows. */
    fun languagesChanged(codes: List<String>) {
        if (codes.isEmpty()) return
        mutableMine.value = codes
        refreshOptions()
    }

    /** Adds [code] to the writer's languages (an "Add languages" screen calls this). */
    suspend fun add(code: String) = languagesChanged(withContext(Dispatchers.IO) { languages.add(code) })

    /** Removes [code] from the writer's languages; the last one stays. */
    suspend fun remove(code: String) = languagesChanged(withContext(Dispatchers.IO) { languages.remove(code) })

    /** Moves [code] to [index] in the writer's languages (Lab, lang-selector: the short list's order). */
    suspend fun move(code: String, index: Int) = languagesChanged(withContext(Dispatchers.IO) { languages.move(code, index) })

    /** The variety the writer's profile names for [code] (for Castilianize's flag), once [load]ed. */
    fun varietyOf(code: String): String? = profileCache.firstOrNull { it.language == code }?.variety

    private fun refreshOptions() {
        mutableOptions.value = optionsFor()
        mutableSuggested.value = LanguageScope.suggested(dataCache, mutableMine.value)
    }

    private fun optionsFor(): List<LanguageOption> =
        LanguageScope.options(mutableMine.value, mutableCurrent.value).filterNotNull()
            .map { code -> LanguageOption(code, profileCache.firstOrNull { it.language == code }?.variety) }

    /** The writer chose [code] on the selector; null is Linguize (Auto). Kept for next time. */
    fun select(code: String?) {
        if (code != null && !LanguageScope.isCode(code)) return
        val stored = LanguageScope.stored(code)
        if (choice.current != stored) choice.current = stored
        mutableCurrent.value = code
        if (code != null && mutableOptions.value.none { it.code == code }) mutableOptions.value = mutableOptions.value + LanguageOption(code)
    }

    /**
     * A conversation in [code] opened (from History, say). Under Linguize every conversation is
     * already in scope, so nothing changes; under another language, the app follows it, so
     * Back to the list shows the conversation it came from.
     */
    fun follow(code: String) {
        val current = mutableCurrent.value ?: return
        if (current != code) select(code)
    }

    companion object {
        private const val HISTORY_SAMPLE = 200

        @Volatile private var instance: AppLanguage? = null

        /** The app's one instance, so every screen sees the same language. */
        fun of(context: Context): AppLanguage = instance ?: synchronized(this) {
            instance ?: create(App.of(context)).also { instance = it }
        }

        private fun create(app: App): AppLanguage = AppLanguage(
            // Settings is read off the main thread: on load, an edit, or the watch below.
            languages = object : LanguageList {
                override val codes get() = app.settings.myLanguages.codes
                override fun add(code: String) = app.settings.myLanguages.add(code)
                override fun remove(code: String) = app.settings.myLanguages.remove(code)
                override fun move(code: String, index: Int) = app.settings.myLanguages.move(code, index)
            },
            choice = LanguageScopePreferences(app),
            profiles = { withContext(Dispatchers.IO) { app.settings.writingProfiles } },
            dataLanguages = {
                val targets = withContext(Dispatchers.IO) {
                    runCatching { app.history.recent(HISTORY_SAMPLE).first() }.getOrDefault(emptyList()).map { it.targetLanguage }
                }
                ChatStart.mostUsed(targets)
            },
        ).also { language ->
            // Lab, language-generic: a language added or removed anywhere reaches the selector.
            app.appScope.launch {
                app.settings.myLanguages.changes().collect { withContext(Dispatchers.Main) { language.languagesChanged(it) } }
            }
        }
    }

    /** Before [load], and in tests: Catalan and Spanish, fixed. */
    private object DefaultLanguages : LanguageList {
        override val codes get() = LanguageScope.DEFAULT_LANGUAGES
        override fun add(code: String) = codes
        override fun remove(code: String) = codes
        override fun move(code: String, index: Int) = codes
    }
}
