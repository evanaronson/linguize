package com.evanaronson.linguize.ui.language

import android.content.Context
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.language.LanguageChoice
import com.evanaronson.linguize.data.language.LanguageScopePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One language on the switch, with the variety the writer's profile names (for its flag). */
data class LanguageOption(val code: String, val variety: String? = null)

/**
 * Lab, Language scope: the writer's current language, shared by the whole app. Chat lists
 * and starts conversations in it, Write checks in it, Progress shows only it, and History
 * starts filtered to it. A switch is kept ([LanguageChoice]); until the first one, the
 * current language is the chat's start language ([ChatStart.language]: the last one used in
 * the chat, the selection menu's only language, the most checked one, Spanish).
 *
 * Checks from other apps (selection menu, accessibility button, tile, share) aren't scoped:
 * they detect the language as before, and simply file under it.
 */
class AppLanguage internal constructor(
    private val choice: LanguageChoice,
    /** The language to start in when the writer hasn't switched yet. */
    private val fallback: suspend () -> String,
    /** The writer's writing profiles, in order: the languages they write in, and each one's variety. */
    private val profiles: suspend () -> List<WritingProfile>,
    /** Languages found in their History, most used first. */
    private val dataLanguages: suspend () -> List<String>,
) {
    private val mutableCurrent = MutableStateFlow(choice.current?.takeIf(LanguageScope::isCode))
    private val mutableOptions = MutableStateFlow(
        LanguageScope.options(Language.all.map { it.code }, emptyList(), mutableCurrent.value).map { LanguageOption(it) },
    )

    /** The current language; null only until [load] has worked out the default. */
    val current: StateFlow<String?> = mutableCurrent.asStateFlow()

    /** What the switch lists. */
    val options: StateFlow<List<LanguageOption>> = mutableOptions.asStateFlow()

    /** Works out the default (when nothing was chosen) and the languages to offer. Safe to call again. */
    suspend fun load() {
        if (mutableCurrent.value == null) {
            val language = LanguageScope.current(choice.current, runCatching { fallback() }.getOrDefault(ChatStart.FALLBACK_LANGUAGE))
            mutableCurrent.compareAndSet(null, language)
        }
        val profiles = runCatching { profiles() }.getOrDefault(emptyList())
        val data = runCatching { dataLanguages() }.getOrDefault(emptyList())
        val codes = LanguageScope.options(profiles.map { it.language }, data, mutableCurrent.value)
        mutableOptions.value = codes.map { code -> LanguageOption(code, profiles.firstOrNull { it.language == code }?.variety) }
    }

    /** The writer switched to [code] (or opened a conversation in it): kept for next time. */
    fun select(code: String) {
        if (!LanguageScope.isCode(code)) return
        if (choice.current != code) choice.current = code
        mutableCurrent.value = code
        if (mutableOptions.value.none { it.code == code }) mutableOptions.value = mutableOptions.value + LanguageOption(code)
    }

    companion object {
        private const val HISTORY_SAMPLE = 200

        @Volatile private var instance: AppLanguage? = null

        /** The app's one instance, so every screen sees the same language. */
        fun of(context: Context): AppLanguage = instance ?: synchronized(this) {
            instance ?: create(App.of(context)).also { instance = it }
        }

        private fun create(app: App): AppLanguage {
            val recentTargets = suspend {
                withContext(Dispatchers.IO) {
                    runCatching { app.history.recent(HISTORY_SAMPLE).first() }.getOrDefault(emptyList()).map { it.targetLanguage }
                }
            }
            return AppLanguage(
                choice = LanguageScopePreferences(app),
                fallback = {
                    val targets = recentTargets()
                    withContext(Dispatchers.IO) {
                        ChatStart.language(
                            lastUsed = runCatching { ChatPreferences(app).lastLanguage }.getOrNull(),
                            menuLanguages = runCatching { app.menu.enabled() }.getOrDefault(emptySet()).mapNotNull { it.language?.code },
                            historyMostUsedFirst = ChatStart.mostUsed(targets),
                            supported = Language.all.map { it.code },
                        )
                    }
                },
                profiles = { withContext(Dispatchers.IO) { app.settings.writingProfiles } },
                dataLanguages = { ChatStart.mostUsed(recentTargets()) },
            )
        }
    }
}
