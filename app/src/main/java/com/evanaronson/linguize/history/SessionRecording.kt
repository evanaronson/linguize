package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import java.security.MessageDigest

/** Facts about a session known when it starts: how its check runs, and about this install. */
data class SessionContext(
    val opening: Opening,
    val appVersion: String,
    val deviceId: String,
)

/**
 * Turns what happens on one card into history records. Pure Kotlin: it never touches
 * storage, the caller saves what it returns. One instance per session, which is one
 * per card, whatever changes while it's up (the language, the settings: see [runWith]).
 *
 * The caller reports, in order: the start (constructor), each attempt as it finishes,
 * every change to the revision the writer makes (accept, accept all, undo), copies,
 * changes of settings ([runWith]) or language ([changeLanguage]), and finally the close. See docs/history-spec.md, "Decisions, defined".
 *
 * The suggestions decided at the close are those of the *decided revision*: the last
 * one an attempt offered. An attempt that offers none (it failed, or found the text
 * unclear) leaves the previous revision decided, because the card keeps that revision's
 * accepted changes and applies them if the writer replaces the text; only an attempt
 * with a revision of its own makes the previous one's suggestions [Decision.Superseded].
 * What was accepted and what was copied is tracked per revision, since a re-check carries
 * identical accepted edits over as new edits ([Revision.acceptMatching]). A change to a
 * revision other than the decided one (a late report from an earlier attempt) is ignored,
 * and so is everything once closed.
 */
class SessionRecording(
    text: String,
    context: SessionContext,
    now: Long,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** The session as it stands; save it after the constructor and after each [attempt]. */
    var session: SessionRecord
        private set

    /** Rows for earlier revisions' suggestions, already decided. */
    private val superseded = mutableListOf<Pending>()

    /** What the card offers; null before the first attempt that offered something. */
    private var revision: Revision? = null

    /** The index of the attempt [revision] came from. */
    private var revisionAttempt = -1

    /** Edits of [revision] accepted at some point, including ones carried over. */
    private val everAccepted = mutableSetOf<Int>()

    /** Edits of [revision] that were in a section's version when it was copied. */
    private val copiedEdits = mutableSetOf<Int>()

    /** Fixes of [revision] left out of a copied version because a rewording in it replaced them. */
    private val retiredInCopy = mutableSetOf<Int>()

    /** Kinds copied at any point in the session, for the outcome. */
    private val copiedKinds = mutableSetOf<EditKind>()

    private var closed: SessionDetail? = null

    /** The language the next attempts ask for (a code, null for auto-detect). */
    private var language: String? = context.opening.requestedLanguage

    /** The settings the next attempts run with. */
    private var settings: SessionSettings = context.opening.settings

    init {
        val opening = context.opening
        val settings = opening.settings
        session = SessionRecord(
            id = newId(),
            deviceId = context.deviceId,
            createdAt = now,
            updatedAt = now,
            startedAt = now,
            origin = opening.origin,
            hostApp = opening.hostApp,
            requestedLanguage = opening.requestedLanguage,
            nativeLanguage = settings.nativeLanguage,
            text = text,
            textHash = hash(text),
            punctuation = settings.punctuation,
            judgments = settings.judgments,
            provider = settings.provider,
            model = settings.model,
            promptHash = settings.promptHash,
            appVersion = context.appVersion,
        )
    }

    /**
     * An attempt finished. [revision] is what the card now offers (null when the attempt
     * failed or the result has nothing to review); when there is one, the previous
     * revision's suggestions become [Decision.Superseded]. [raw] is the model's answer as it
     * came, also kept when it couldn't be read; [status] is what a successful attempt found.
     * [reusedFrom] is the session whose kept answer was shown instead of asking the model.
     * Returns the session to save.
     */
    fun attempt(
        at: Long,
        settled: List<Settled>,
        raw: String?,
        failure: String?,
        failureDetail: String?,
        revision: Revision?,
        meaning: String?,
        status: Verdict.Status? = null,
        reusedFrom: String? = null,
    ): SessionRecord {
        if (closed != null) return session
        val index = session.attempts.size
        if (revision != null) {
            supersede(at)
            this.revision = revision
            revisionAttempt = index
            remember(revision)
        }
        val settings = settings
        val attempt = Attempt(
            at, settled, raw, failure, failureDetail, reusedFrom,
            requestedLanguage = language,
            nativeLanguage = settings.nativeLanguage,
            punctuation = settings.punctuation,
            judgments = settings.judgments,
            provider = settings.provider,
            model = settings.model,
            promptHash = settings.promptHash,
        )
        val succeeded = failure == null
        session = session.copy(
            // The session's language and settings are the latest attempt's.
            requestedLanguage = language,
            nativeLanguage = settings.nativeLanguage,
            punctuation = settings.punctuation,
            judgments = settings.judgments,
            provider = settings.provider,
            model = settings.model,
            promptHash = settings.promptHash,
            updatedAt = maxOf(session.updatedAt, at),
            attempts = session.attempts + attempt,
            meaning = if (succeeded) meaning else session.meaning,
            status = if (succeeded) status ?: session.status else session.status,
        )
        return session
    }

    /**
     * The attempts from now on ask for [language] (a code, null for auto-detect) with
     * [settings]: the settings changed while the card was up. Each attempt records what it
     * ran with, and the session takes it with the attempt. A card is one session whatever
     * changes.
     */
    fun runWith(language: String?, settings: SessionSettings) {
        if (closed != null) return
        this.language = language
        this.settings = settings
    }

    /**
     * The writer picked another [language] on the open card, which checks the text again in
     * it ([runWith], with [settings]). The card starts over (nothing accepted is carried over
     * to a review in another language), so the revision on offer is replaced now, whatever
     * the next attempt brings: its suggestions become [Decision.Superseded].
     */
    fun changeLanguage(language: String?, settings: SessionSettings, at: Long) {
        if (closed != null) return
        runWith(language, settings)
        supersede(at)
        revision = null
        revisionAttempt = -1
    }

    /**
     * The writer accepted or undid something; [revision] is the new state. Ignored before
     * any attempt offered one, and when [revision] isn't a state of the decided one (its
     * edits differ): a change to an earlier attempt's card can't decide this one's.
     */
    fun changed(revision: Revision) {
        val current = this.revision ?: return
        if (closed != null || revision.edits != current.edits) return
        this.revision = revision
        remember(revision)
    }

    /**
     * The writer copied the version shown for [kind]: what [Revision.preview] rendered,
     * the accepted changes and [kind]'s remaining suggestions, less the fixes a rewording
     * among them replaced. Those left the copy inside the rewording, so they end
     * [Decision.Retired] rather than copied.
     */
    fun copied(kind: EditKind) {
        if (closed != null) return
        copiedKinds += kind
        revision?.let { revision ->
            val shown = revision.acceptedEdits + revision.remaining(kind)
            val (replaced, rendered) = shown.partition { fix ->
                fix.kind == EditKind.Fix && shown.any { it.kind == EditKind.Natural && it.touches(fix) }
            }
            copiedEdits += rendered.map { it.id }
            retiredInCopy += replaced.map { it.id }
        }
    }

    /**
     * The card closed. [finalText] is what went back to the app (null if nothing did).
     * Returns the closed session and one suggestion row per suggestion shown in any
     * attempt, each with its decision. Calling it twice returns the same result.
     */
    fun close(at: Long, finalText: String?): SessionDetail {
        closed?.let { return it }
        val applied = finalText != null
        val current = revision?.let { revision ->
            revision.edits.map { Pending(revisionAttempt, it, decide(revision, it, applied), at) }
        }.orEmpty()
        session = session.copy(
            updatedAt = maxOf(session.updatedAt, at),
            closedAt = at,
            finalText = finalText,
            outcome = when {
                applied -> Outcome.Applied
                copiedKinds.isNotEmpty() -> Outcome.Copied
                session.attempts.lastOrNull()?.failure != null -> Outcome.Failed
                else -> Outcome.None
            },
        )
        val rows = (superseded + current).map { it.record(session, at) }
        return SessionDetail(session, rows).also { closed = it }
    }

    /** The revision on offer is replaced: its suggestions are decided as [Decision.Superseded]. */
    private fun supersede(at: Long) {
        revision?.let { previous ->
            previous.edits.forEach { superseded += Pending(revisionAttempt, it, Decision.Superseded, at) }
        }
        everAccepted.clear()
        copiedEdits.clear()
        retiredInCopy.clear()
    }

    private fun remember(revision: Revision) {
        everAccepted += revision.acceptedEdits.map { it.id }
    }

    /**
     * The decision for one of the decided revision's edits, as things stand at the close.
     * When nothing went back to the app ([applied] false), nothing is accepted: what was
     * accepted ends copied (it left in a copy) or undone.
     */
    private fun decide(revision: Revision, edit: Edit, applied: Boolean): Decision = if (applied) {
        when {
            revision.isApplied(edit.id) -> Decision.Accepted
            revision.isRetired(edit.id) -> Decision.Retired
            edit.id in copiedEdits -> Decision.Copied
            edit.id in retiredInCopy -> Decision.Retired
            edit.id in everAccepted -> Decision.Undone
            else -> Decision.Ignored
        }
    } else {
        when {
            edit.id in copiedEdits -> Decision.Copied
            // A fix inside a rewording that was copied left with the rewording.
            edit.id in retiredInCopy -> Decision.Retired
            revision.isRetired(edit.id) && retiredByCopied(revision, edit) -> Decision.Retired
            edit.id in everAccepted -> Decision.Undone
            else -> Decision.Ignored
        }
    }

    private fun retiredByCopied(revision: Revision, fix: Edit) =
        revision.acceptedEdits.any { it.kind == EditKind.Natural && it.id in copiedEdits && it.touches(fix) }

    /** A suggestion whose decision is known, waiting for the close to become a row. */
    private inner class Pending(val attempt: Int, val edit: Edit, val decision: Decision, val decidedAt: Long) {
        fun record(session: SessionRecord, at: Long) = SuggestionRecord(
            id = newId(),
            sessionId = session.id,
            deviceId = session.deviceId,
            createdAt = at,
            updatedAt = at,
            attempt = attempt,
            kind = edit.kind,
            start = edit.start,
            end = edit.end,
            fromText = edit.from,
            toText = edit.replacement,
            why = edit.why,
            decision = decision,
            decidedAt = decidedAt,
        )
    }

    companion object {
        /** Short, stable hash of a text, for finding the same text checked again. */
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}
