package com.evanaronson.linguize.data

import android.content.Context
import com.evanaronson.linguize.R
import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import com.evanaronson.linguize.llm.Provider

/**
 * The choices made on the settings screen. API keys live in [ApiKeys]; which
 * languages appear in the selection menu lives with the menu entries themselves.
 * Choices are stored as their constants' tokens ([Tokens]); values saved by earlier builds
 * (constant names) still read, and anything unknown reads as the default. A model is kept
 * under its provider's token; one saved under the provider's name by an earlier build is
 * moved there on first use.
 */
class Settings(private val context: Context) : CheckPreferences {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).apply {
        moveProviderNamesToTokens { modelKey(it) }
    }

    override var provider: Provider
        get() = read(PROVIDER, Provider.tokens, Provider.Gemini)
        set(value) = write(PROVIDER, value.token)

    override var punctuation: Punctuation
        get() = read(PUNCTUATION, Punctuation.tokens, Punctuation.Moderate)
        set(value) = write(PUNCTUATION, value.token)

    /** Migrates the former global punctuation once, then reads independent complete profiles. */
    override val writingProfiles: List<WritingProfile>
        get() = synchronized(writingProfilesLock) {
            if (!prefs.contains(WRITING_PROFILES)) {
                val migrated = Language.all.map { WritingProfile.defaultFor(it, punctuation) }
                prefs.edit().putString(WRITING_PROFILES, profileJson.encodeToString(migrated)).apply()
                migrated
            } else readStoredWritingProfiles().take(Language.all.size)
        }

    /** V1 edits one profile per supported language; identity and name survive every edit. */
    fun updateWritingProfile(profile: WritingProfile) = synchronized(writingProfilesLock) {
        require(Language.forCode(profile.language) != null) { "Unsupported writing language" }
        writingProfiles // Complete first-use migration before editing the stored records.
        val existing = readStoredWritingProfiles()
        require(existing.any { it.id == profile.id && it.language == profile.language }) { "Unknown writing profile" }
        val profiles = existing.map { if (it.id == profile.id) profile else it }
        prefs.edit().putString(WRITING_PROFILES, profileJson.encodeToString(profiles)).apply()
    }

    private fun readStoredWritingProfiles(): List<WritingProfile> {
        val raw = runCatching { prefs.getString(WRITING_PROFILES, null) }.getOrNull()
        val records = raw?.takeIf { it.length <= 65_536 }?.let {
            runCatching { profileJson.parseToJsonElement(it) as? JsonArray }.getOrNull()
        }
        // A malformed entry cannot erase another language's usable profile. Unknown records
        // are retained when editing supported languages, allowing forward-compatible storage.
        val decoded = records.orEmpty().mapNotNull { entry ->
            runCatching { profileJson.decodeFromJsonElement<WritingProfile>(entry) }.getOrNull()
        }.distinctBy { it.id }
        val supported = Language.all.map { language ->
            decoded.firstOrNull { it.language == language.code } ?: WritingProfile.defaultFor(language)
        }
        val selectedIds = supported.map { it.id }.toSet()
        return supported + decoded.filter { it.id !in selectedIds }.sortedWith(compareBy({ it.language }, { it.id }))
    }

    override var judgments: Judgments
        get() = read(JUDGMENTS, Judgments.tokens, Judgments.Both)
        set(value) = write(JUDGMENTS, value.token)

    /** Placement shared by loading and result popups. */
    var popupPosition: PopupPosition
        get() = read(POPUP_POSITION, PopupPosition.tokens, PopupPosition.Bottom)
        set(value) = write(POPUP_POSITION, value.token)

    /** Whether checks are kept in history. Turning it off keeps what's already there. */
    var historyEnabled: Boolean
        get() = prefs.getBoolean(HISTORY, true)
        set(value) = synchronized(historyLock) {
            val edit = prefs.edit().putBoolean(HISTORY, value)
            if (!value && historyEnabled) edit.putLong(HISTORY_EPOCH, historyEpoch + 1)
            edit.apply()
        }

    /**
     * Advances whenever recording is turned off. A card opened before that change stays
     * unrecorded even if history is enabled again before its next result or close.
     * Stored with the toggle in one preferences edit, shared by every Settings instance.
     */
    val historyEpoch: Long
        get() = prefs.getLong(HISTORY_EPOCH, 0L)

    /**
     * A random id made once per install, so history rows from several phones can merge later.
     * Made under a lock and committed before it's returned, so two first checks can't make
     * two ids. The app reads it once at start, off the main thread, so checks find it made.
     */
    val deviceId: String
        get() = prefs.getString(DEVICE_ID, null) ?: synchronized(deviceIdLock) {
            prefs.getString(DEVICE_ID, null)
                ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).commit() }
        }

    /** The writer's own language: the app's UI language, which the meaning and reasons are written in. */
    override val nativeLanguage: String
        get() = context.getString(R.string.native_language)

    /** The chosen model, or null for the provider's recommended one. */
    override fun model(provider: Provider): String? = prefs.getString(modelKey(provider.token), null)

    fun setModel(provider: Provider, model: String?) = write(modelKey(provider.token), model)

    private fun <E : Enum<E>> read(key: String, tokens: Tokens<E>, default: E): E =
        tokens.decodeOrName(prefs.getString(key, null)) ?: default

    private fun write(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    private companion object {
        /** Where the model chosen for the provider with [token] is kept. */
        fun modelKey(token: String) = "model.$token"

        const val PROVIDER = "provider"
        const val POPUP_POSITION = "popupPosition"
        const val PUNCTUATION = "punctuation"
        const val WRITING_PROFILES = "writingProfiles.v1"
        val profileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val writingProfilesLock = Any()

        const val JUDGMENTS = "judgments"
        const val HISTORY = "history"
        const val HISTORY_EPOCH = "historyEpoch"
        const val DEVICE_ID = "deviceId"

        /** One for every Settings, since each reads the same preferences file. */
        val deviceIdLock = Any()
        val historyLock = Any()
    }
}
