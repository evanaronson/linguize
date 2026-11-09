package com.evanaronson.linguize.ui.language

import android.content.Context
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.data.language.LanguageChoice
import com.evanaronson.linguize.data.language.LanguageScopePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One language on the selector, with the variety the writer's profile names (for its flag). */
data class LanguageOption(val code: String, val variety: String? = null)

/**
 * Lab, Language scope: the writer's current language, shared by the whole app and set by the
 * one selector in the top bar of the four tabs. Null is **Linguize** (Auto), the default:
 * Chat lists every conversation, a new one starts in the chat's start language, Notes
 * detects, Progress shows one block per language in use and History shows everything. A
 * language ("ca" for Catalanize, "es" for Castilianize, or another found in the data) scopes
 * all of them to it. A switch is kept ([LanguageChoice]); a language stored before Auto
 * existed is kept as it is.
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
) {
    private val mutableCurrent = MutableStateFlow(LanguageScope.current(choice.current))
    private val mutableOptions = MutableStateFlow(optionsFor(emptyList(), emptyList()))

    /** The current language; null is Linguize (Auto). Known at once, from what was stored. */
    val current: StateFlow<String?> = mutableCurrent.asStateFlow()

    /** The languages the selector lists after Linguize: Catalanize, Castilianize, then any others found. */
    val options: StateFlow<List<LanguageOption>> = mutableOptions.asStateFlow()

    /** Reads the profiles' varieties and the languages found in History. Safe to call again. */
    suspend fun load() {
        val profiles = runCatching { profiles() }.getOrDefault(emptyList())
        val data = runCatching { dataLanguages() }.getOrDefault(emptyList())
        mutableOptions.value = optionsFor(profiles, data)
    }

    private fun optionsFor(profiles: List<WritingProfile>, data: List<String>): List<LanguageOption> =
        LanguageScope.options(data, mutableCurrent.value).filterNotNull()
            .map { code -> LanguageOption(code, profiles.firstOrNull { it.language == code }?.variety) }

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
            choice = LanguageScopePreferences(app),
            profiles = { withContext(Dispatchers.IO) { app.settings.writingProfiles } },
            dataLanguages = {
                val targets = withContext(Dispatchers.IO) {
                    runCatching { app.history.recent(HISTORY_SAMPLE).first() }.getOrDefault(emptyList()).map { it.targetLanguage }
                }
                ChatStart.mostUsed(targets)
            },
        )
    }
}
