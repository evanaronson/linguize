package com.evanaronson.linguize.data

import android.content.Context
import com.evanaronson.linguize.R
import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
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

    override var judgments: Judgments
        get() = read(JUDGMENTS, Judgments.tokens, Judgments.Both)
        set(value) = write(JUDGMENTS, value.token)

    /** Whether checks are kept in history. Turning it off keeps what's already there. */
    var historyEnabled: Boolean
        get() = prefs.getBoolean(HISTORY, true)
        set(value) = prefs.edit().putBoolean(HISTORY, value).apply()

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
        const val PUNCTUATION = "punctuation"
        const val JUDGMENTS = "judgments"
        const val HISTORY = "history"
        const val DEVICE_ID = "deviceId"

        /** One for every Settings, since each reads the same preferences file. */
        val deviceIdLock = Any()
    }
}
