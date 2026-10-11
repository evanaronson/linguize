package com.evanaronson.linguize.llm

import com.evanaronson.linguize.codec.Tokens

/** Why a check couldn't produce a result; the card shows a message per [Reason]. */
class CheckFailure(
    val reason: Reason,
    /** The provider's own explanation, when there is one. */
    detail: String? = null,
    cause: Throwable? = null,
    /** The model's answer, when one came but couldn't be used; history keeps it. */
    val raw: String? = null,
    /** An app-owned explanation, rendered in the current UI language. */
    val userMessage: Message? = null,
) : Exception(detail?.let(::scrubSecrets) ?: reason.name, cause) {
    /** Scrub at the failure boundary, including errors from storage or unexpected exceptions. */
    val detail: String? = detail?.let(::scrubSecrets)?.takeIf { it.isNotEmpty() }

    /** Stable identifiers let history retain the explanation without retaining an English UI string. */
    enum class Message(val token: String) {
        InputTooLong("input_too_long"),
        InvalidKey("invalid_key"),
        EmptyReply("empty_reply"),
        ReplyTooLarge("reply_too_large"),
        InvalidReply("invalid_reply"),
        TruncatedReply("truncated_reply"),
        IncompleteReply("incomplete_reply"),
        Declined("declined"),
        ;

        companion object {
            val tokens = Tokens(entries, Message::token)
        }
    }

    /**
     * Each reason's [token] is what history keeps. For the reasons that existed when history
     * kept constant names, it is the name in snake case, which is how the upgrade rewrites
     * those rows without knowing this enum; a test holds them together.
     */
    enum class Reason(val token: String) {
        NoKey("no_key"),
        BadKey("bad_key"),
        BadModel("bad_model"),
        Offline("offline"),
        Timeout("timeout"),
        RateLimited("rate_limited"),
        Server("server"),
        BadResponse("bad_response"),
        TooLong("too_long"),

        /** The selection menu was asked for more checks than it allows in a minute; nothing was sent. */
        TooMany("too_many"),
        ;

        companion object {
            val tokens = Tokens(entries, Reason::token)
        }
    }
}
