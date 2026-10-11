package com.evanaronson.linguize.history

import com.evanaronson.linguize.codec.TokenSerializer
import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.Serializable

/*
 * The history data model: what is stored on the phone and, later, what a sync would
 * upload as-is. Rows use client-made UUIDs, epoch-millisecond UTC timestamps, soft
 * deletion and a schema version, so rows from several devices can merge on a server
 * without translation. See docs/history-spec.md.
 *
 * Enums are kept as the lowercase tokens their constants spell out (`token`), the same
 * in the database and the export, and read leniently: see [Tokens]. The export is
 * written from the rows themselves, so a token this build doesn't know travels unchanged.
 */

/**
 * Where a check was started. [recorded] says whether it goes into history: only checks of
 * text from other apps do.
 */
@Serializable(with = OriginSerializer::class)
enum class Origin(val token: String, val recorded: Boolean) {
    /** The selection menu of another app. */
    Menu("menu", recorded = true),

    /** The accessibility button, on another app's field. */
    Button("button", recorded = true),

    /**
     * The "Try it" card in the app itself: never recorded. Builds before database version 4
     * recorded it; the upgrade to 4 deletes those sessions.
     */
    Tester("tester", recorded = false),
    ;

    companion object {
        val tokens = Tokens(entries, Origin::token)
    }
}

/** What became of one suggestion by the time its card closed. */
@Serializable(with = DecisionSerializer::class)
enum class Decision(val token: String) {
    /** Applied in the final text. */
    Accepted("accepted"),

    /** Accepted at some point, not applied at the end. */
    Undone("undone"),

    /** A fix overtaken by an accepted rewording that includes it. */
    Retired("retired"),

    /** Still on offer when the writer copied its section's version. */
    Copied("copied"),

    /** Still on offer when the card closed. */
    Ignored("ignored"),

    /** From an earlier attempt that a re-check, or a check in another language, replaced. */
    Superseded("superseded"),
    ;

    companion object {
        val tokens = Tokens(entries, Decision::token)
    }
}

/** How a session ended. */
@Serializable(with = OutcomeSerializer::class)
enum class Outcome(val token: String) {
    /** Accepted changes went back to the app. */
    Applied("applied"),

    /** Nothing applied; a version was copied. */
    Copied("copied"),

    /** Closed with nothing applied or copied. */
    None("none"),

    /** The last attempt failed. */
    Failed("failed"),

    /** Never closed: the process ended with the card open. Set on the next start. */
    Abandoned("abandoned"),
    ;

    companion object {
        val tokens = Tokens(entries, Outcome::token)
    }
}

/** One time the card opened on a text, and everything that happened until it closed. */
@Serializable
data class SessionRecord(
    val id: String,
    val deviceId: String,
    val schema: Int = SCHEMA,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val startedAt: Long,
    /** Null while the card is open. */
    val closedAt: Long? = null,
    /** Null only when read from a row whose origin this build doesn't know. */
    val origin: Origin?,
    /** Package name of the app the text came from; null when unknown. */
    val hostApp: String? = null,
    /**
     * The language the writer asked for, as its ISO 639-1 code (`Language.code`); null for
     * auto-detect. Like the settings below, the latest attempt's: the language and settings
     * can change while the card is up, and each attempt keeps its own ([reuseKeyOf]).
     */
    val requestedLanguage: String? = null,
    /**
     * The writer's own language, which the meaning and reasons were asked in; null for
     * sessions recorded before it was kept.
     */
    val nativeLanguage: String? = null,
    /** The text that was checked. */
    val text: String,
    /** Short hash of [text], to find the same text checked again. */
    val textHash: String,
    /** What went back to the app; null when nothing was applied. */
    val finalText: String? = null,
    /** Null while open. */
    val outcome: Outcome? = null,
    /** The settings the latest attempt ran with, as tokens: they change what gets suggested. See [settings]. */
    val punctuation: String,
    val judgments: String,
    val provider: String,
    val model: String,
    /** Hash of the system prompt and schema, so verdicts from different prompts aren't compared as equal. */
    val promptHash: String,
    val appVersion: String,
    /** Every request made on this card, oldest first, each with the language and settings it ran with. */
    val attempts: List<Attempt> = emptyList(),
    /** What the text means, from the last successful attempt. */
    val meaning: String? = null,
    /**
     * What the last successful attempt found: ok (there's a review, maybe with nothing to
     * suggest), unclear, or wrong_language. Null when no attempt succeeded.
     */
    val status: Verdict.Status? = null,
) {
    /** The settings this session's latest attempt ran with. */
    val settings: SessionSettings
        get() = SessionSettings(nativeLanguage, punctuation, judgments, provider, model, promptHash)

    /** The latest attempt's text, language and settings. */
    val reuseKey: ReuseKey
        get() = ReuseKey(textHash, requestedLanguage, settings)

    /**
     * What another check must match to show [attempt]'s (an index into [attempts]) verdict
     * again: the text, and the language and settings that attempt ran with. Rows from before
     * schema 4 didn't keep them per attempt, and every attempt of theirs ran with the
     * session's. Null when there's no such attempt, or it lacks the settings.
     */
    fun reuseKeyOf(attempt: Int): ReuseKey? {
        if (schema < 4) return reuseKey
        val ran = attempts.getOrNull(attempt) ?: return null
        val settings = SessionSettings(
            nativeLanguage = ran.nativeLanguage,
            punctuation = ran.punctuation ?: return null,
            judgments = ran.judgments ?: return null,
            provider = ran.provider ?: return null,
            model = ran.model ?: return null,
            promptHash = ran.promptHash ?: return null,
        )
        return ReuseKey(textHash, ran.requestedLanguage, settings)
    }

    companion object {
        /**
         * 2: `nativeLanguage`; an attempt's answer is `raw`, the model's own text.
         * 3: `requestedLanguage` is a language code, an attempt's `failure` a token.
         * 4: one session per card; an attempt keeps the language and settings it ran with,
         *    and the session's are the latest attempt's.
         */
        const val SCHEMA = 4
    }
}

/** One request to the model within a session: the first check, a re-check, or a check in another language. */
@Serializable
data class Attempt(
    val at: Long,
    /** The writer's answers to assumptions sent with this request. */
    val settled: List<Settled> = emptyList(),
    /**
     * The model's answer exactly as it came back, before it was read: normally the JSON the
     * schema asks for, but kept as well when it couldn't be read (a `BadResponse` failure).
     * Null when no answer came (offline, a refused key, a cut-off answer, ...).
     */
    val raw: String? = null,
    /** Why the attempt failed, as the reason's token (`CheckFailure.Reason.token`); null when it didn't. */
    val failure: String? = null,
    val failureDetail: String? = null,
    /**
     * The session whose kept answer was shown again instead of asking the model; null
     * when the model was asked.
     */
    val reusedFrom: String? = null,
    /*
     * The language and settings this request ran with, as the session keeps them (see
     * [SessionRecord.reuseKeyOf]). Null in rows written before schema 4, which ran every
     * attempt with the session's; the requested language is also null for auto-detect.
     */
    val requestedLanguage: String? = null,
    val nativeLanguage: String? = null,
    val punctuation: String? = null,
    val judgments: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val promptHash: String? = null,
) {
    /** Whether a verdict came back and was read: the card showed it. */
    val succeeded: Boolean get() = failure == null && raw != null
}

/** One suggestion the writer saw, and what they did with it. */
@Serializable
data class SuggestionRecord(
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val schema: Int = SessionRecord.SCHEMA,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Index into the session's attempts. */
    val attempt: Int,
    @Serializable(with = EditKindSerializer::class)
    val kind: EditKind,
    /** Position in the session's text. */
    val start: Int,
    val end: Int,
    val fromText: String,
    val toText: String,
    val why: String? = null,
    val decision: Decision,
    val decidedAt: Long,
)

/**
 * The settings a check runs with that change its answer, as a session keeps them: the
 * options and provider as tokens, the writer's own language as the prompt names it. They
 * can change while a card is up; each attempt keeps the ones it ran with.
 */
data class SessionSettings(
    /** The writer's own language; null for sessions recorded before it was kept. */
    val nativeLanguage: String?,
    val punctuation: String,
    val judgments: String,
    val provider: String,
    val model: String,
    /** Hash of the system prompt and schema. */
    val promptHash: String,
)

/** Everything a check must share with an earlier attempt for that attempt's verdict to be shown again. */
data class ReuseKey(
    val textHash: String,
    /** A language code, or null for auto-detect. */
    val requestedLanguage: String?,
    val settings: SessionSettings,
)

/** A row in the Recent list. */
data class SessionSummary(
    val id: String,
    val startedAt: Long,
    /** Null when it's one this build doesn't know. */
    val origin: Origin?,
    val hostApp: String?,
    /** The start of the text checked, at most [TEXT_PREFIX] characters. */
    val text: String,
    val outcome: Outcome?,
    val fixes: Int,
    val rewordings: Int,
    /** Suggestions that ended Accepted. */
    val taken: Int,
    /** [SessionRecord.status]: what the last successful attempt found; null when none succeeded. */
    val status: Verdict.Status? = null,
) {
    companion object {
        /** How much of a session's text a Recent row carries. */
        const val TEXT_PREFIX = 300
    }
}

/** A session with its suggestions, for the detail view; one line of the export has its shape. */
@Serializable
data class SessionDetail(val session: SessionRecord, val suggestions: List<SuggestionRecord>) {
    /**
     * The [decided attempt][decidedAttempt] when it ran with [key]'s text, language and
     * settings, so its verdict can be shown again for a check with [key]; else null.
     */
    fun keptFor(key: ReuseKey): Int? = decidedAttempt()?.takeIf { session.reuseKeyOf(it) == key }

    /**
     * The attempt the session's card ended on, whose answer is shown again (on its page, or
     * reused by a later check): the one the decided suggestions came from (those not
     * [Decision.Superseded]), or else the last attempt that succeeded. Null when none did.
     * The one place that decides it.
     */
    fun decidedAttempt(): Int? {
        val succeeded = session.attempts.indices.filter { session.attempts[it].succeeded }
        val decided = suggestions.filter { it.decision != Decision.Superseded }.maxOfOrNull { it.attempt }
        return decided?.takeIf { it in succeeded } ?: succeeded.lastOrNull()
    }
}

object OriginSerializer : TokenSerializer<Origin>("Origin", { Origin.tokens })

object DecisionSerializer : TokenSerializer<Decision>("Decision", { Decision.tokens })

object OutcomeSerializer : TokenSerializer<Outcome>("Outcome", { Outcome.tokens })

object EditKindSerializer : TokenSerializer<EditKind>("EditKind", { EditKind.tokens })
