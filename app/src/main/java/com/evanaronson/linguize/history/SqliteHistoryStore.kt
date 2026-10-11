package com.evanaronson.linguize.history

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * [HistoryStore] on the phone's SQLite, with the SQL written out in [HistorySchema].
 * The framework's SQLiteOpenHelper rather than Room: two tables don't need code generation.
 *
 * Creating it touches nothing; the database is opened (and made, the first time) by the
 * first call, on [Dispatchers.IO] like every other. The observed queries re-run after any
 * write made through this store.
 *
 * Nothing throws but cancellation: a failure (the database can't be opened or upgraded,
 * a write fails, a row can't be read) is logged and answered with nothing.
 *
 * Deleting is a hard delete for now. The rows have `deletedAt` so a deletion can travel
 * once there's a sync; until then there's nothing to carry it, and the writer asked for
 * the text to be gone: `secure_delete` overwrites what's deleted, a delete checkpoints the
 * write-ahead log so no copy is left there, and [clear] also vacuums.
 */
class SqliteHistoryStore(
    context: Context,
    name: String = HistorySchema.NAME,
) : HistoryStore {
    private val context = context.applicationContext
    private val name = name
    private val helper = Helper(this.context, name)

    /** Sessions opened here, and ones cleared or deleted, which an open card mustn't write back. */
    private val forgotten = Forgotten()

    /** Bumped after every write, so the observed queries run again. */
    private val changes = MutableStateFlow(0L)

    override suspend fun save(session: SessionRecord) = write("save a session") { db ->
        if (forgotten.saving(session.id)) db.execute(HistorySchema.SAVE_SESSION, session.values().values)
    }

    override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = write("close a session") { db ->
        if (!forgotten.closing(session.id)) return@write
        db.execute(HistorySchema.CLOSE_SESSION, session.values().values)
        // Closing twice writes the same rows again rather than adding to them.
        db.execute(HistorySchema.DELETE_SUGGESTIONS_OF, listOf(session.id))
        val insert = db.compileStatement(HistorySchema.INSERT_SUGGESTION)
        try {
            for (suggestion in suggestions) {
                insert.clearBindings()
                insert.bindAll(suggestion.values().values)
                insert.executeInsert()
            }
        } finally {
            insert.close()
        }
    }

    /**
     * Closes the sessions left open but those this store saved, read in the same transaction,
     * so a check that opens while this runs isn't caught. Doesn't make the database when
     * there isn't one yet: then there's nothing to mark.
     */
    override suspend fun markAbandoned(now: Long) {
        val exists = withContext(Dispatchers.IO) { context.getDatabasePath(name).exists() }
        if (!exists) return
        write("mark abandoned sessions") { db ->
            val keep = forgotten.openHere()
            db.execute(HistorySchema.markAbandoned(keep.size), listOf(now, now) + keep)
        }
    }

    override fun count(): Flow<Int> = observe("count sessions", 0) { db ->
        db.select(HistorySchema.COUNT) { it.long("n").toInt() }.single()
    }

    override fun since(): Flow<Long?> = observe("read the oldest session", null) { db ->
        db.select(HistorySchema.SINCE) { it.longOrNull("since") }.single()
    }

    override fun recent(limit: Int): Flow<List<SessionSummary>> = observe("read recent sessions", emptyList()) { db ->
        db.select(HistorySchema.recent(limit), read = ::sessionSummary)
    }

    override suspend fun detail(id: String): SessionDetail? = read("read a session", null) { db -> db.detail(id) }

    /**
     * The query finds sessions whose latest attempt ran with [key]'s language and settings;
     * the one returned is the newest whose last attempt succeeded and whose decided attempt
     * ran with them itself ([SessionDetail.keptFor]): within a session they can change.
     */
    override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = read("look for a kept answer", null) { db ->
        db.select(HistorySchema.reusable(key), HistorySchema.reusableArgs(key, since), ::sessionRecord)
            .asSequence()
            .filter { it.lastAttemptSucceeded() }
            .map { SessionDetail(it, db.suggestions(it.id)) }
            .firstOrNull { it.keptFor(key) != null }
    }

    /** Removes the session and, by cascade, its suggestions. */
    override suspend fun delete(id: String) {
        write("delete a session") { db ->
            forgotten.deleted(id)
            db.execute(HistorySchema.DELETE_SESSION, listOf(id))
        }
        read("checkpoint after a delete", Unit) { db -> db.checkpoint() }
    }

    override suspend fun clear() {
        write("clear history") { db ->
            forgotten.cleared()
            HistorySchema.CLEAR.forEach(db::execSQL)
        }
        // Outside the transaction, which VACUUM can't run in: rebuilds the file without the
        // freed pages, then empties the write-ahead log, which can still hold copies of them.
        read("vacuum after clearing", Unit) { db ->
            db.execSQL("VACUUM")
            db.checkpoint()
        }
    }

    /**
     * Oldest first, each row as it's stored ([rowJson]): nothing is decoded and encoded
     * again, so what a newer build wrote leaves as it was. Leaves [out] open; the caller
     * closes it.
     */
    override suspend fun export(out: OutputStream): Boolean = read("export history", false) { db ->
        val writer = out.bufferedWriter()
        // One transaction, so a delete during the export can't split a session from its suggestions.
        db.beginTransaction()
        try {
            db.eachRow(HistorySchema.ALL_SESSIONS) { row ->
                val session = rowJson(row, HistorySchema.SESSION_COLUMNS)
                val suggestions = db.select(HistorySchema.SESSION_SUGGESTIONS, listOf(row.string("id"))) {
                    rowJson(it, HistorySchema.SUGGESTION_COLUMNS)
                }
                writer.write(exportLine(session, suggestions))
                writer.newLine()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        writer.flush()
        true
    }

    private fun SQLiteDatabase.detail(id: String): SessionDetail? {
        val session = select(HistorySchema.SESSION, listOf(id), ::sessionRecord).firstOrNull() ?: return null
        return SessionDetail(session, suggestions(id))
    }

    /** A session's suggestions, leaving out any this build can't read. */
    private fun SQLiteDatabase.suggestions(sessionId: String) =
        select(HistorySchema.SESSION_SUGGESTIONS, listOf(sessionId), ::suggestionRecord).filterNotNull()

    /** Runs [block] in a transaction on [Dispatchers.IO], then tells the observed queries. Logs a failure. */
    private suspend fun write(what: String, block: (SQLiteDatabase) -> Unit) {
        val written = read(what, false) { db ->
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        }
        if (written) changes.update { it + 1 }
    }

    /** [block] on [Dispatchers.IO] with the database; [fallback] when it fails. */
    private suspend fun <T> read(what: String, fallback: T, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        guarded(what, fallback) { block(helper.writableDatabase) }
    }

    /** [read] now and again after every write, on [Dispatchers.IO]; [fallback] when it fails. */
    private fun <T> observe(what: String, fallback: T, read: (SQLiteDatabase) -> T): Flow<T> = changes
        .map { guarded(what, fallback) { read(helper.writableDatabase) } }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, HistorySchema.VERSION) {
        init {
            // Lets the observed queries read while a close is being written.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            // Deleted rows are overwritten with zeros rather than left in free pages.
            db.pragma("secure_delete = ON")
        }

        override fun onCreate(db: SQLiteDatabase) {
            HistorySchema.CREATE.forEach(db::execSQL)
        }

        /**
         * Runs [HistorySchema.upgrade] (which, from before version 4, deletes the tester's
         * sessions) and rewrites each session's attempts, in the one
         * transaction the framework opens for it. A version with no way up (none exists
         * today, but a refusal here would turn history off for good, with only a log line)
         * is started afresh instead: the old rows are dropped, and that is logged.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            val statements = HistorySchema.upgrade(oldVersion, db.columns(HistorySchema.SESSIONS))
            if (statements == null) {
                Log.w(TAG, "No way to upgrade history from version $oldVersion to $newVersion; starting afresh")
                HistorySchema.DROP.forEach(db::execSQL)
                onCreate(db)
                return
            }
            statements.forEach(db::execSQL)
            val rewritten = buildList {
                db.eachRow(HistorySchema.ALL_ATTEMPTS) { row ->
                    val stored = row.string("attempts")
                    val upgraded = upgradeAttempts(stored, HistorySchema.ATTEMPT_CONTEXT.associateWith(row::stringOrNull))
                    if (upgraded != stored) add(listOf(upgraded, row.string("id")))
                }
            }
            rewritten.forEach { db.execute(HistorySchema.SET_ATTEMPTS, it) }
        }
    }
}

/** A PRAGMA that answers with a row, which execSQL refuses: runs it and reads the answer. */
private fun SQLiteDatabase.pragma(pragma: String) {
    rawQuery("PRAGMA $pragma", null).use { it.moveToFirst() }
}

/** Copies the write-ahead log into the database and empties it. */
private fun SQLiteDatabase.checkpoint() = pragma("wal_checkpoint(TRUNCATE)")

/** The names of [table]'s columns. */
private fun SQLiteDatabase.columns(table: String): Set<String> =
    rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
    }

private const val TAG = "History"

/** History must never break the app: anything but cancellation is logged and gives [fallback]. */
private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Couldn't $what", e)
    fallback
}

private class CursorRow(private val cursor: Cursor) : Row {
    override fun stringOrNull(column: String): String? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getString(index)
    }

    override fun longOrNull(column: String): Long? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getLong(index)
    }
}

/** Calls [each] for every row of [sql]. Numbers in [args] compare as numbers against INTEGER columns. */
private fun SQLiteDatabase.eachRow(sql: String, args: List<String> = emptyList(), each: (Row) -> Unit) {
    rawQuery(sql, args.toTypedArray()).use { cursor ->
        val row = CursorRow(cursor)
        while (cursor.moveToNext()) each(row)
    }
}

private fun <T> SQLiteDatabase.select(sql: String, args: List<String> = emptyList(), read: (Row) -> T): List<T> =
    buildList { eachRow(sql, args) { add(read(it)) } }

/** Runs one statement with [values] bound in order; returns the rows changed. */
private fun SQLiteDatabase.execute(sql: String, values: Collection<Any?>): Int {
    val statement = compileStatement(sql)
    try {
        statement.bindAll(values)
        return statement.executeUpdateDelete()
    } finally {
        statement.close()
    }
}

private fun SQLiteStatement.bindAll(values: Collection<Any?>) {
    values.forEachIndexed { i, value ->
        when (value) {
            null -> bindNull(i + 1)
            is String -> bindString(i + 1, value)
            is Int -> bindLong(i + 1, value.toLong())
            is Long -> bindLong(i + 1, value)
            else -> error("History can't store a ${value::class.simpleName}")
        }
    }
}
