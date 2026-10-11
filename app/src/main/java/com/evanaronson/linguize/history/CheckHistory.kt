package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What history needs from the app besides its store. Read off the main thread when a
 * session opens (the first read can mean loading preferences, or making the device id);
 * [enabled] is read again before every write, by then cheaply.
 */
interface HistoryEnvironment {
    /** Whether checks are recorded. Read before every write. */
    val enabled: Boolean

    /** A random id made once per install. */
    val deviceId: String

    /** This build's version name. */
    val appVersion: String
}

/**
 * How a check runs: where it was started, and the language and settings it asks with. The
 * language and settings can change while the card is up ([recheck][CheckHistory.recheck],
 * [languageChanged][CheckHistory.languageChanged]); the card stays one session, and each
 * attempt records what it ran with.
 */
data class Opening(
    /** Where the check was started; only an [Origin.recorded] one is recorded. */
    val origin: Origin,
    /** The app the text came from, when known. */
    val hostApp: String?,
    /** The language asked for, as its code (`Language.code`); null for auto-detect. */
    val requestedLanguage: String?,
    val settings: SessionSettings,
)

/** A verdict kept from an earlier session on the same text and settings, to show again. */
data class Kept(
    /** The earlier session's id. */
    val sessionId: String,
    /** The model's answer, as stored ([Attempt.raw]). */
    val raw: String,
    /** The writer's answers to assumptions it was made with. */
    val settled: List<Settled>,
)

/** The answer a first attempt shows: the model's, or a [reused] kept one. */
data class Answer<T>(val value: T, val reused: Kept?)

/**
 * The history of the card's checks: opens a session when a check starts, records each
 * attempt, the writer's changes and copies, and closes it with what went back to the app.
 * Everything that decides what's recorded lives here or in [SessionRecording]; the view
 * model only forwards to it.
 *
 * - Writes never hold up the card: they're queued on [scope] (which outlives the screen)
 *   in the order they were made, and their failures are only logged.
 * - [HistoryEnvironment.enabled] is read before every write. Turned off while a card is
 *   open, the open session stops being recorded: nothing more is written for it, even
 *   when it's turned on again while the card is up.
 * - Only checks of text from other apps are recorded ([Origin.recorded]): one started in
 *   the app itself ("Try it") opens no session, looks up nothing and writes nothing.
 * - A text over [maxChars] isn't recorded: the check refuses it before any request.
 * - The first attempt of a session, and the first in another language, races the model
 *   against a kept verdict for the same text, language and settings (see [firstAttempt]);
 *   the request leaves before any history work. A kept verdict is an attempt's, matched by
 *   the language and settings that attempt ran with ([SessionDetail.keptFor]).
 * - A card is one session, whatever changes while it's up: other settings ([recheck]) or
 *   another language ([languageChanged]). Each attempt records the language and settings
 *   it ran with, and the session has the latest attempt's.
 *
 * Not thread-safe: call it from one thread (the main thread, in the app).
 */
class CheckHistory(
    private val store: HistoryStore,
    private val environment: HistoryEnvironment,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val maxChars: Int = Int.MAX_VALUE,
    private val reuseWithin: Long = REUSE_WITHIN_MS,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Where [environment] is first read when a session opens. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The open session's record; null when history is off or no card is open. */
    private var recording: SessionRecording? = null

    /**
     * Whether the card's session has been decided, opened or refused ([open] got past
     * reading the environment, or found the check isn't recorded), until [close]. Once it
     * has, a language change never opens one: a card that wasn't recorded from its start,
     * or stopped being recorded when history was turned off, stays unrecorded.
     */
    private var decided = false

    /** The last write queued, so writes land in the order they were made. */
    private var lastWrite: Job? = null

    /** The open session as it stands, or null. */
    internal val session: SessionRecord? get() = recording?.session

    /**
     * Starts a fresh check of [text]: starts [ask] (the model request) at once, then opens
     * a session for it and, while the request runs, looks for a kept verdict. A kept
     * verdict that arrives before the model's answer is turned into a result by [reuse]
     * and shown instead, and the request is cancelled; otherwise the model's answer is
     * returned (or its failure thrown) as soon as it comes, whatever the lookup is doing,
     * so the lookup can never add latency. Closes nothing: [close] the previous session first.
     */
    suspend fun <T> firstAttempt(
        text: String,
        opening: Opening,
        ask: suspend () -> T,
        reuse: suspend (Kept) -> T?,
    ): Answer<T> = race(ask, reuse) { open(text, opening)?.reuseKey }

    /**
     * The writer picked another language on the open card, and [text] is checked again in
     * it ([opening] has the new [Opening.requestedLanguage], and the settings as they are
     * now): the open session gets the attempt, so a card is one session however often its
     * language changes. The revision it offered is superseded ([SessionRecording.changeLanguage]).
     * Like [firstAttempt], starts [ask] at once and races it against a kept verdict for the
     * text in the new language. When the card's first check was cancelled before it opened
     * its session, opens one as [firstAttempt] would; a card that isn't recorded (history
     * was off, or turned off since) stays unrecorded.
     */
    suspend fun <T> languageChanged(
        text: String,
        opening: Opening,
        ask: suspend () -> T,
        reuse: suspend (Kept) -> T?,
    ): Answer<T> = race(ask, reuse) { switchLanguage(text, opening) }

    /**
     * Starts [ask] at once, then runs [prepare], which gives what the attempt must match in
     * a kept verdict (or null when nothing is recorded), and while the request runs looks for
     * one. See [firstAttempt].
     */
    private suspend fun <T> race(
        ask: suspend () -> T,
        reuse: suspend (Kept) -> T?,
        prepare: suspend () -> ReuseKey?,
    ): Answer<T> = coroutineScope {
        // Undispatched: the request is on its way before anything below runs.
        val answer = async(start = CoroutineStart.UNDISPATCHED) { capture { ask() } }
        val key = prepare()
        val since = clock() - reuseWithin
        // Not a child: a blocking query that's slow must never be waited for.
        val lookup: Deferred<Kept?>? = key?.let { scope.async { find(it, since) } }
        try {
            val kept = lookup?.let {
                select<Kept?> {
                    answer.onAwait { null }
                    lookup.onAwait { it }
                }
            }
            if (kept != null && answer.isActive) {
                val reused = quietly("show a kept answer") { reuse(kept) }
                if (reused != null && answer.isActive) {
                    answer.cancel()
                    return@coroutineScope Answer(reused, kept)
                }
            }
            Answer(answer.await().getOrThrow(), null)
        } finally {
            lookup?.cancel()
        }
    }

    /**
     * Before a re-check of the open session's text with [opening]: when the settings
     * changed while the card was up (the provider, model, options, prompt or native
     * language), the attempts from now on record the new ones, and so does the session with
     * the next attempt. It stays one session: a card is one, whatever changes. Does nothing
     * when no session is open.
     */
    fun recheck(opening: Opening) {
        val recording = recording ?: return
        quietly("change the settings") { recording.runWith(opening.requestedLanguage, opening.settings) }
    }

    /**
     * Before the attempt in another language: the open session's next attempts run with
     * [opening]'s language and settings, and its card starts over; or, when the card's first
     * check didn't get as far as [open], one is opened. Returns what a kept verdict must
     * match, or null when nothing is recorded.
     */
    private suspend fun switchLanguage(text: String, opening: Opening): ReuseKey? {
        val recording = recording ?: return if (decided) null else open(text, opening)?.reuseKey
        quietly("change the language") { recording.changeLanguage(opening.requestedLanguage, opening.settings, clock()) } ?: return null
        return ReuseKey(recording.session.textHash, opening.requestedLanguage, opening.settings)
    }

    /**
     * Opens a session for [text], saved right away; returns it, or null when it isn't
     * recorded (the origin isn't [recorded][Origin.recorded], history is off, or the text
     * is over [maxChars]). The environment is read on [io].
     */
    internal suspend fun open(text: String, opening: Opening): SessionRecord? {
        recording = null
        decided = false
        val context = if (opening.origin.recorded && text.length <= maxChars) {
            withContext(io) {
                quietly("open a session") {
                    if (!environment.enabled) return@quietly null
                    SessionContext(opening, appVersion = environment.appVersion, deviceId = environment.deviceId)
                }
            }
        } else {
            null
        }
        // Decided, either way; a cancellation while the environment was read leaves it undecided.
        decided = true
        context ?: return null
        val started = quietly("open a session") { SessionRecording(text, context, clock(), newId) } ?: return null
        recording = started
        val session = started.session
        write("save a session") { store.save(session) }
        return session.takeIf { recording != null }
    }

    /** An attempt came back with [result], from the model's answer [raw] or a kept one ([reusedFrom]). */
    fun succeeded(settled: List<Settled>, raw: String, result: CheckResult, reusedFrom: String? = null) {
        val recording = recording ?: return
        val reviewed = result as? CheckResult.Reviewed
        val session = quietly("record an attempt") {
            recording.attempt(
                at = clock(),
                settled = settled,
                raw = raw,
                failure = null,
                failureDetail = null,
                revision = reviewed?.revision,
                meaning = reviewed?.meaning?.takeIf { it.isNotBlank() },
                status = statusOf(result),
                reusedFrom = reusedFrom,
            )
        } ?: return
        write("save an attempt") { store.save(session) }
    }

    /**
     * An attempt failed for [reason] (a `CheckFailure.Reason` token). [raw] is the model's
     * answer when one came but couldn't be read.
     */
    fun failed(settled: List<Settled>, reason: String, detail: String?, raw: String? = null) {
        val recording = recording ?: return
        val session = quietly("record a failed attempt") {
            recording.attempt(clock(), settled, raw, reason, detail, null, null)
        } ?: return
        write("save an attempt") { store.save(session) }
    }

    /** The writer accepted or undid something; [revision] is the new state. */
    fun changed(revision: Revision) {
        recording?.let { quietly("record a change") { it.changed(revision) } }
    }

    /** The writer copied the version shown for [kind]. */
    fun copied(kind: EditKind) {
        recording?.let { quietly("record a copy") { it.copied(kind) } }
    }

    /** Closes the open session, if any, with what went back to the app ([finalText], null for nothing). */
    fun close(finalText: String?) {
        decided = false
        val recording = recording ?: return
        this.recording = null
        val closed = quietly("close a session") { recording.close(clock(), finalText) } ?: return
        write("write a closed session") { store.close(closed.session, closed.suggestions) }
    }

    /**
     * Blocks the calling thread until the writes queued so far are done, for at most
     * [timeoutMs]: for a host whose process may be killed as soon as it finishes, so the
     * close it just queued isn't lost. Gives up quietly at the limit; the writes go on.
     */
    fun awaitWrites(timeoutMs: Long) {
        val pending = lastWrite?.takeIf { it.isActive } ?: return
        quietly("wait for history writes") { runBlocking { withTimeoutOrNull(timeoutMs) { pending.join() } } }
    }

    /** Waits for the writes queued so far. */
    internal suspend fun flush() {
        lastWrite?.join()
    }

    /**
     * The newest kept answer for [key] made [since] then, or null. Matched per attempt: the
     * attempt shown again must itself have run with [key]'s language and settings, since
     * they can change within a session ([SessionDetail.keptFor]; the store matches the same,
     * this holds whatever store it is). Never throws.
     */
    private suspend fun find(key: ReuseKey, since: Long): Kept? = quietly("look for a kept answer") {
        val kept = store.reusable(key, since) ?: return@quietly null
        val index = kept.keptFor(key) ?: return@quietly null
        val attempt = kept.session.attempts[index]
        Kept(kept.session.id, attempt.raw ?: return@quietly null, attempt.settled)
    }

    /**
     * Queues a write on [scope] after the ones queued before it. When history has been
     * turned off, the open session stops being recorded and nothing is written.
     */
    private fun write(what: String, block: suspend () -> Unit) {
        if (quietly("read whether history is on") { environment.enabled } != true) {
            recording = null
            return
        }
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            quietly(what) { block() }
        }
    }

    /** History must never break a check: a failure is logged and gives null. */
    private inline fun <R> quietly(what: String, block: () -> R): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("History: couldn't $what", e)
        null
    }

    companion object {
        /** How recent a kept answer must be to be shown again instead of asking the model. */
        const val REUSE_WITHIN_MS = 10 * 60 * 1000L

        /** What a result says the model found. */
        fun statusOf(result: CheckResult): Verdict.Status = when (result) {
            is CheckResult.Reviewed -> Verdict.Status.Ok
            CheckResult.Unclear -> Verdict.Status.Unclear
            is CheckResult.WrongLanguage -> Verdict.Status.WrongLanguage
        }
    }
}

/** [block]'s value or what it threw, but never swallowing cancellation. */
private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
