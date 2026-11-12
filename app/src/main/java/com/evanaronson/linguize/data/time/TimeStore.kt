package com.evanaronson.linguize.data.time

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.evanaronson.linguize.core.time.TimeLog
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeSweep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lab, Time spent: measurements in their own SQLite database (`lab_time_spent`), one row per
 * History session, apart from History (whose schema doesn't change). Only measurements are
 * stored; estimates are worked out when read. Rows go with their session: a deleted
 * conversation deletes its rows ([delete]), and [sweep] drops rows whose session History no
 * longer has. A failure is logged and reads as nothing, so timing never breaks the app.
 */
class TimeStore(
    context: Context,
    private val scope: CoroutineScope,
    name: String? = DATABASE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TimeLog {
    private val helper = Helper(context.applicationContext, name)
    private val mutations = Any()
    /** Queued writes die with the process; deleted IDs block those still waiting in this one. */
    private val deleted = mutableSetOf<String>()

    /** Saves [record] off the main thread; a later record for the same session replaces it. */
    override fun record(record: TimeRecord) {
        scope.launch { save(record) }
    }

    suspend fun save(record: TimeRecord): Boolean = io("save a time record", false) { db ->
        synchronized(mutations) {
            if (record.sessionId in deleted) return@synchronized false
            db.insertWithOnConflict(TABLE, null, record.values(), SQLiteDatabase.CONFLICT_REPLACE) != -1L
        }
    }

    // TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md
    /**
     * Adds each of [records] whose session has no record here yet, in one transaction; a record
     * already here is never replaced, so nothing is counted twice. Null when it couldn't.
     */
    suspend fun insertIfAbsent(records: List<TimeRecord>): Int? = io("add transferred time records", null) { db ->
        synchronized(mutations) {
            db.beginTransaction()
            try {
                var added = 0
                for (record in records) {
                    if (record.sessionId in deleted) continue
                    if (db.insertWithOnConflict(TABLE, null, record.values(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
                }
                db.setTransactionSuccessful()
                added
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Every record, oldest first. */
    suspend fun all(): List<TimeRecord> = io("read time records", emptyList()) { db ->
        db.rawQuery("SELECT * FROM $TABLE ORDER BY at", null).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.record()?.let(::add) }
        }
    }

    /** Deletes the records of these History sessions. */
    suspend fun delete(sessionIds: Collection<String>): Int {
        synchronized(mutations) { deleted += sessionIds }
        return io("delete time records", 0) { db -> deleteRows(db, sessionIds) }
    }

    /** True even when already absent; false is a retryable failure, never a zero-row success. */
    suspend fun deleteAcknowledged(sessionIds: Collection<String>): Boolean {
        synchronized(mutations) { deleted += sessionIds }
        return io("delete time records", false) { db -> deleteRows(db, sessionIds); true }
    }

    private fun deleteRows(db: SQLiteDatabase, sessionIds: Collection<String>): Int = synchronized(mutations) {
        sessionIds.chunked(MAX_ARGS).sumOf { chunk ->
            db.delete(TABLE, "session_id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
        }
    }

    /** Deletes records whose session isn't in [sessions] any more (see [TimeSweep]). Returns how many. */
    suspend fun sweep(sessions: Set<String>, now: Long): Int {
        val orphans = TimeSweep.orphans(all(), sessions, now)
        return if (orphans.isEmpty()) 0 else delete(orphans)
    }

    suspend fun clear(): Boolean = io("clear time records", false) { db ->
        db.delete(TABLE, null, null)
        true
    }

    /** Closes the database; for tests. */
    fun close() = helper.close()

    private suspend fun <T> io(what: String, fallback: T, block: (SQLiteDatabase) -> T): T = withContext(dispatcher) {
        try {
            block(helper.writableDatabase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't $what", e)
            fallback
        }
    }

    private fun TimeRecord.values() = ContentValues().apply {
        put("session_id", sessionId)
        put("message_id", messageId)
        put("origin", origin)
        put("language", language)
        put("writing_ms", writingMs)
        put("review_ms", reviewMs)
        put("words", words)
        put("changes_shown", changesShown)
        put("changes_accepted", changesAccepted)
        put("at", at)
        put("rules", rules)
    }

    private fun Cursor.record(): TimeRecord? = runCatching {
        TimeRecord(
            sessionId = getString(getColumnIndexOrThrow("session_id")),
            origin = getString(getColumnIndexOrThrow("origin")),
            language = stringOrNull("language"),
            writingMs = getColumnIndexOrThrow("writing_ms").let { if (isNull(it)) null else getLong(it) },
            reviewMs = getLong(getColumnIndexOrThrow("review_ms")),
            words = getInt(getColumnIndexOrThrow("words")),
            changesShown = getInt(getColumnIndexOrThrow("changes_shown")),
            changesAccepted = getInt(getColumnIndexOrThrow("changes_accepted")),
            at = getLong(getColumnIndexOrThrow("at")),
            rules = getInt(getColumnIndexOrThrow("rules")),
            messageId = stringOrNull("message_id"),
        )
    }.onFailure { Log.w(TAG, "Skipped an unreadable time record", it) }.getOrNull()

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    session_id TEXT PRIMARY KEY NOT NULL,
                    message_id TEXT,
                    origin TEXT NOT NULL,
                    language TEXT,
                    writing_ms INTEGER,
                    review_ms INTEGER NOT NULL,
                    words INTEGER NOT NULL,
                    changes_shown INTEGER NOT NULL,
                    changes_accepted INTEGER NOT NULL,
                    at INTEGER NOT NULL,
                    rules INTEGER NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX time_by_at ON $TABLE(at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        /** The database file's name. */
        const val DATABASE = "lab_time_spent"
        private const val VERSION = 1
        private const val TABLE = "messages"
        private const val TAG = "LabTimeSpent"

        /** SQLite's default limit on bound arguments is 999. */
        private const val MAX_ARGS = 500
    }
}
