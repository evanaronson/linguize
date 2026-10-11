package com.evanaronson.linguize.data

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.Provider

/**
 * The choices a check reads, as [Settings] keeps them. An interface so what runs checks can
 * be tested without Android.
 */
interface CheckPreferences {
    val provider: Provider
    val punctuation: Punctuation
    val judgments: Judgments

    /** The writer's own language: the meaning and reasons are written in it. */
    val nativeLanguage: String

    /** The chosen model for [provider], or null for its recommended one. */
    fun model(provider: Provider): String?
}

/** A provider's saved API key, as [ApiKeys] keeps them; null when there's none (or it can't be read). */
fun interface ProviderKeys {
    fun get(provider: Provider): String?
}
