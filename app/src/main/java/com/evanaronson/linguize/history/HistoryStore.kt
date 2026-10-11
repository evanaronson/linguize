package com.evanaronson.linguize.history

import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Where history is kept. One implementation today (SQLite, on the phone); the rest of
 * the app only sees this interface. All functions are safe to call from the main
 * thread: implementations move their own work off it.
 *
 * Nothing here throws but cancellation: a store that can't be read or written logs it
 * and answers with nothing (an empty list, null, zero, false), because history must
 * never take the app down with it.
 *
 * [clear] and [delete] are final for the sessions they remove, including one still open
 * on a card: a later [save] or [close] of a session open when the last [clear] ran, or
 * of one [delete]d, is dropped rather than bringing it back.
 */
interface HistoryStore {
    /** Adds a session as it opens, or updates it while it's open. Never changes a closed session. */
    suspend fun save(session: SessionRecord)

    /** Closes a session: writes its final row and its suggestions, in one transaction. */
    suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>)

    /**
     * Closes, as [Outcome.Abandoned] at [now], every session left open that this process
     * didn't open: the process that opened it ended with its card open.
     */
    suspend fun markAbandoned(now: Long)

    /** The number of sessions Recent can show: closed and not deleted. */
    fun count(): Flow<Int>

    /** The date of the oldest session [count] counts, or null when there are none. */
    fun since(): Flow<Long?>

    /** Newest first, closed sessions only, not counting deleted ones. */
    fun recent(limit: Int): Flow<List<SessionSummary>>

    suspend fun detail(id: String): SessionDetail?

    /**
     * The most recent closed session matching [key], started at or after [since], whose
     * last attempt succeeded, with its suggestions: the answer of its
     * [decided attempt][SessionDetail.decidedAttempt] can be shown again without asking the model.
     */
    suspend fun reusable(key: ReuseKey, since: Long): SessionDetail?

    suspend fun delete(id: String)

    /** Deletes everything, leaving none of the text in the database's files. */
    suspend fun clear()

    /**
     * Writes every kept session with its suggestions as JSON Lines, one session per line in
     * [SessionDetail]'s shape, written from the rows as they are stored. Returns false when
     * it couldn't (the database or [out] failed); what was written to [out] by then is
     * incomplete and shouldn't be shared.
     */
    suspend fun export(out: OutputStream): Boolean
}

/**
 * Which sessions this process opened and which it removed, so that [HistoryStore.clear]
 * and [HistoryStore.delete] are final for a session still open on a card, and so that
 * [HistoryStore.markAbandoned] knows which open sessions are someone else's. A store
 * consults it inside the same transaction as the write, so a clear and a save can't
 * interleave. Ids, not times: the wall clock can move backwards (a corrected clock, a
 * manual change), and a comparison of times would then drop new sessions or keep old ones
 * open for good.
 *
 * A session opened before a clear but whose first save only reaches the store after it
 * is kept: the clear didn't see it. Saves are queued the moment a card opens, so this
 * needs a clear within milliseconds of a check starting.
 */
internal class Forgotten {
    /** Sessions saved by this process and not yet closed, cleared or deleted. */
    private val open = mutableSetOf<String>()

    /** Sessions cleared or deleted in this process: never written again. */
    private val gone = mutableSetOf<String>()

    /** A session is being saved: true when it may be, and then it counts as opened here. */
    @Synchronized
    fun saving(id: String): Boolean {
        if (id in gone) return false
        open += id
        return true
    }

    /** A session is being closed: true when it may be. */
    @Synchronized
    fun closing(id: String): Boolean {
        if (id in gone) return false
        open -= id
        return true
    }

    /** Everything was cleared: the sessions still open on a card are gone too. */
    @Synchronized
    fun cleared() {
        gone += open
        open.clear()
    }

    @Synchronized
    fun deleted(id: String) {
        gone += id
        open -= id
    }

    /** The sessions this process opened that are still open: not abandoned. */
    @Synchronized
    fun openHere(): List<String> = open.toList()
}
