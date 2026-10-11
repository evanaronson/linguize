package com.evanaronson.linguize.core

import com.evanaronson.linguize.codec.Tokens
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The model's answer, exactly as assets/check_schema.json defines it. */
@Serializable
data class Verdict(
    val status: Status,
    /** The language the model judged the text as, in English ("Catalan"); empty when unclear. */
    val language: String = "",
    /** What the writer means, in their native language. */
    val meaning: String = "",
    /** Readings of ambiguous parts that the fixes and rewordings rely on; usually none. */
    val assumptions: List<Assumption> = emptyList(),
    @SerialName("has_errors") val hasErrors: Boolean = false,
    /** The full text with only the necessary corrections. */
    val corrected: String = "",
    val fixes: List<VerdictChange> = emptyList(),
    @SerialName("more_natural") val moreNatural: Boolean = false,
    /** The corrected text with the suggested rewordings on top. */
    val natural: String = "",
    @SerialName("natural_changes") val naturalChanges: List<VerdictChange> = emptyList(),
) {
    /** Each constant's [token] is its name in the answer's JSON too; a test holds the two together. */
    @Serializable
    enum class Status(
        /** What history keeps. */
        val token: String,
    ) {
        @SerialName("ok") Ok("ok"),
        @SerialName("unclear") Unclear("unclear"),
        @SerialName("wrong_language") WrongLanguage("wrong_language"),
        ;

        companion object {
            val tokens = Tokens(entries, Status::token)
        }
    }
}

/**
 * A reading the model chose for something ambiguous, e.g. who did something.
 * [alternatives] are other plausible readings, offered only if the writer says it's wrong.
 */
@Serializable
data class Assumption(
    val about: String,
    val assumed: String,
    val words: String = "",
    val alternatives: List<String> = emptyList(),
)

/** The writer's own answer to an assumption, sent with a re-check so the model follows it. Kept in history as is. */
@Serializable
data class Settled(val about: String, val answer: String)

/** One change as the model describes it; used for its reason, not its position. */
@Serializable
data class VerdictChange(val from: String = "", val to: String = "", val why: String = "")
