package com.evanaronson.linguize.llm

import com.evanaronson.linguize.codec.Tokens

/** Why a check couldn't produce a result; the card shows a message per [Reason]. */
class CheckFailure(
    val reason: Reason,
    /** The provider's own explanation, when there is one. */
    val detail: String? = null,
    cause: Throwable? = null,
    /** The model's answer, when one came but couldn't be used; history keeps it. */
    val raw: String? = null,
) : Exception(detail ?: reason.name, cause) {
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
