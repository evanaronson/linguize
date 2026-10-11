package com.evanaronson.linguize

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider

/**
 * The settings a check runs with, read once so the request and the record of it agree.
 * The one place that says what affects a check: history keeps it as [stored], and a card
 * compares it to tell whether settings changed since it was checked.
 */
data class CheckContext(
    val provider: Provider,
    val model: String,
    val punctuation: Punctuation,
    val judgments: Judgments,
    /** The writer's own language, which the meaning and reasons are written in. */
    val native: String,
    /** [Prompt.hash] of the instructions and schema sent. */
    val promptHash: String,
) {
    /** As history keeps it, with tokens for the options and provider. */
    val stored: SessionSettings
        get() = SessionSettings(
            nativeLanguage = native,
            punctuation = punctuation.token,
            judgments = judgments.token,
            provider = provider.token,
            model = model,
            promptHash = promptHash,
        )
}
