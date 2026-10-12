package com.evanaronson.linguize.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider

/** UI labels stay separate from stable tokens used by prompts, settings, and history. */
@get:StringRes
val Punctuation.labelResource: Int
    get() = when (this) {
        Punctuation.Strict -> R.string.punctuation_strict
        Punctuation.Moderate -> R.string.punctuation_moderate
        Punctuation.Casual -> R.string.punctuation_casual
    }

@get:StringRes
val Punctuation.descriptionResource: Int
    get() = when (this) {
        Punctuation.Strict -> R.string.punctuation_strict_description
        Punctuation.Moderate -> R.string.punctuation_moderate_description
        Punctuation.Casual -> R.string.punctuation_casual_description
    }

@get:StringRes
val Judgments.labelResource: Int
    get() = when (this) {
        Judgments.Both -> R.string.judgments_both
        Judgments.FixOnly -> R.string.judgments_fix
        Judgments.NaturalizeOnly -> R.string.judgments_rewording
    }

@get:StringRes
val Provider.labelResource: Int
    get() = when (this) {
        Provider.Gemini -> R.string.provider_gemini
        Provider.OpenAI -> R.string.provider_openai
    }

/** Translate known language names without changing their prompt or history values. */
@Composable
fun Language.displayName(): String = when (code) {
    Language.Catalan.code -> stringResource(R.string.language_catalan)
    Language.Spanish.code -> stringResource(R.string.language_spanish)
    else -> name
}

@Composable
fun languageDisplayName(stored: String): String = Language.read(stored)?.displayName() ?: stored

@get:StringRes
val CheckFailure.Reason.titleResource: Int
    get() = when (this) {
        CheckFailure.Reason.NoKey -> R.string.failure_no_key
        CheckFailure.Reason.BadKey -> R.string.failure_bad_key
        CheckFailure.Reason.BadModel -> R.string.failure_bad_model
        CheckFailure.Reason.Offline -> R.string.failure_offline
        CheckFailure.Reason.Timeout -> R.string.failure_timeout
        CheckFailure.Reason.RateLimited -> R.string.failure_rate_limited
        CheckFailure.Reason.Server -> R.string.failure_server
        CheckFailure.Reason.BadResponse -> R.string.failure_bad_response
        CheckFailure.Reason.TooLong -> R.string.failure_too_long
        CheckFailure.Reason.TooMany -> R.string.failure_too_many
    }

@get:StringRes
val CheckFailure.Reason.hintResource: Int?
    get() = when (this) {
        CheckFailure.Reason.NoKey -> R.string.hint_no_key
        CheckFailure.Reason.BadKey -> R.string.hint_bad_key
        CheckFailure.Reason.BadModel -> R.string.hint_bad_model
        CheckFailure.Reason.Offline -> R.string.hint_offline
        CheckFailure.Reason.RateLimited -> R.string.hint_rate_limited
        CheckFailure.Reason.TooLong -> R.string.hint_too_long
        CheckFailure.Reason.TooMany -> R.string.hint_too_many
        else -> null
    }

@get:StringRes
val CheckFailure.Message.textResource: Int
    get() = when (this) {
        CheckFailure.Message.InputTooLong -> R.string.message_input_too_long
        CheckFailure.Message.InvalidKey -> R.string.message_invalid_key
        CheckFailure.Message.EmptyReply -> R.string.message_empty_reply
        CheckFailure.Message.ReplyTooLarge -> R.string.message_reply_too_large
        CheckFailure.Message.InvalidReply -> R.string.message_invalid_reply
        CheckFailure.Message.TruncatedReply -> R.string.message_truncated_reply
        CheckFailure.Message.IncompleteReply -> R.string.message_incomplete_reply
        CheckFailure.Message.Declined -> R.string.message_declined
    }

/** Some explanations include live limits; keep the number next to the rule that owns it. */
@Composable
fun CheckFailure.Message.localizedText(): String = when (this) {
    CheckFailure.Message.InputTooLong -> stringResource(textResource, Checker.MAX_CHARS)
    else -> stringResource(textResource)
}
