package com.evanaronson.linguize.data.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.ChatSnapshot
import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.SentCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Work left after removing a conversation, or after rewinding one to edit a message
 * ([rewoundFrom], the edited message's id: the conversation stays). IDs only; no message text
 * is copied into the queue.
 */
data class PendingChatDeletion(val conversationId: String, val sessionIds: List<String>, val rewoundFrom: String? = null)

/** Malformed work stays in the database without preventing independent valid cleanup. */
data class PendingChatDeletions(val entries: List<PendingChatDeletion>, val hasUnreadableEntries: Boolean)

sealed interface PartnerAppend {
    data class Saved(val snapshot: ChatSnapshot) : PartnerAppend
    data object Superseded : PartnerAppend
    data object Failed : PartnerAppend
}

/**
 * Lab Chat's conversations and messages, in their own SQLite database (`lab_chat`), apart from
 * History and settings. Calls run on [Dispatchers.IO] by default. Coordinator reads throw so
 * an unavailable database is not mistaken for missing content; legacy convenience reads retain their fallbacks.
 */
class ChatStore(
    context: Context,
    name: String? = DATABASE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val helper = Helper(context.applicationContext, name)

    /** Every conversation, most recently active first. Legacy callers retain the empty fallback. */
    suspend fun conversations(): List<ConversationSummary> = io("list conversations", emptyList()) { it.summaries() }

    /** A failed read throws; an empty successful read is an actual empty list. */
    suspend fun readConversations(): List<ConversationSummary> = withContext(dispatcher) { helper.readableDatabase.summaries() }

    /** Conversation and messages come from one transaction. Only a successful missing read returns null. */
    suspend fun readThread(id: String): ChatSnapshot? = withContext(dispatcher) {
        helper.readableDatabase.let { db -> db.transaction { db.snapshot(id) } }
    }

    private fun SQLiteDatabase.summaries(): List<ConversationSummary> =
        rawQuery(
            """SELECT c.*,
                 (SELECT m.text FROM messages m WHERE m.conversation = c.id ORDER BY m.at DESC, m.seq DESC LIMIT 1) AS last_text,
                 (SELECT m.from_writer FROM messages m WHERE m.conversation = c.id ORDER BY m.at DESC, m.seq DESC LIMIT 1) AS last_from_writer,
                 (SELECT COUNT(*) FROM messages m WHERE m.conversation = c.id AND m.from_writer = 1) AS writer_messages
               FROM conversations c ORDER BY c.updated_at DESC, c.created_at DESC""",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ConversationSummary(cursor.conversation(), cursor.stringOrNull("last_text"), cursor.int("writer_messages"),
                        cursor.int("last_from_writer") == 1))
                }
            }
        }

    suspend fun conversation(id: String): Conversation? = io("read a conversation", null) { db ->
        db.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.conversation() else null }
    }

    /** [id]'s messages, oldest first. */
    suspend fun messages(id: String): List<ChatMessage> = io("read messages", emptyList()) { db -> db.messages(id) }

    suspend fun create(conversation: Conversation): Boolean = io("create a conversation", false) { db ->
        db.insertOrThrow(CONVERSATIONS, null, conversation.values())
        true
    }

    /** Adds [message] and makes its conversation the most recently active. */
    suspend fun add(message: ChatMessage): Boolean = io("save a message", false) { db ->
        db.transaction { db.insertMessage(message) }
        true
    }

    /** The writer's acknowledgement includes every turn committed before this message. */
    suspend fun appendWriter(message: ChatMessage): ChatSnapshot? = io("save a writer message", null) { db ->
        db.transaction {
            db.insertMessage(message)
            checkNotNull(db.snapshot(message.conversationId))
        }
    }

    /**
     * A generated turn belongs to one request and one preceding turn. Recheck both after IO
     * dispatch, in the same transaction as the append. [permitted] must be safe across threads.
     */
    suspend fun appendPartner(
        message: ChatMessage,
        afterMessageId: String?,
        title: String?,
        permitted: () -> Boolean,
    ): PartnerAppend = io<PartnerAppend>("save a partner message", PartnerAppend.Failed) { db ->
        db.transaction {
            val last = db.rawQuery(
                "SELECT id FROM messages WHERE conversation = ? ORDER BY at DESC, seq DESC LIMIT 1",
                arrayOf(message.conversationId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (last != afterMessageId || !permitted()) return@transaction PartnerAppend.Superseded
            db.insertMessage(message)
            if (title != null) {
                // The provider cannot overwrite a title chosen while its answer was in flight.
                db.update(CONVERSATIONS, ContentValues().apply { put("title", title) },
                    "id = ? AND title_custom = 0 AND title = ''", arrayOf(message.conversationId))
            }
            PartnerAppend.Saved(checkNotNull(db.snapshot(message.conversationId)))
        }
    }

    private fun SQLiteDatabase.insertMessage(message: ChatMessage) {
        insertOrThrow(MESSAGES, null, message.values())
        execSQL("UPDATE conversations SET updated_at = MAX(updated_at, ?) WHERE id = ?", arrayOf<Any>(message.at, message.conversationId))
    }

    private fun SQLiteDatabase.snapshot(id: String): ChatSnapshot? {
        val conversation = rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.conversation() else null } ?: return null
        return ChatSnapshot(conversation, messages(id))
    }

    suspend fun rename(id: String, title: String, custom: Boolean, permitted: () -> Boolean = { true }): Boolean =
        io("rename a conversation", false) { db ->
            db.transaction {
                if (!permitted()) return@transaction false
                db.update(CONVERSATIONS, ContentValues().apply {
                    put("title", title)
                    put("title_custom", if (custom) 1 else 0)
                }, "id = ?", arrayOf(id)) > 0
            }
        }

    suspend fun setLearned(id: String, count: Int): Boolean = io("note what memory learned", false) { db ->
        db.update(CONVERSATIONS, ContentValues().apply { put("learned_count", count) }, "id = ?", arrayOf(id)) > 0
    }

    /** The History sessions [conversationId]'s messages were saved as. */
    suspend fun sessionIds(conversationId: String): List<String> = io("read a conversation's checks", emptyList()) { db ->
        db.rawQuery("SELECT session_id FROM messages WHERE conversation = ? AND session_id IS NOT NULL", arrayOf(conversationId))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    /**
     * For each of [sessionIds] saved from a chat message, its conversation: History labels the
     * message with it and links to it. Sessions from elsewhere are left out.
     */
    suspend fun threads(sessionIds: Collection<String>): Map<String, ChatThreadInfo> = io("read chat threads", emptyMap()) { db ->
        if (sessionIds.isEmpty()) return@io emptyMap()
        val byConversation = HashMap<String, ChatThreadInfo>()
        val out = HashMap<String, ChatThreadInfo>()
        sessionIds.distinct().chunked(SQL_ARGS).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery(
                """SELECT m.session_id, c.id, c.title, c.persona, c.language,
                     (SELECT COUNT(*) FROM messages w WHERE w.conversation = c.id AND w.from_writer = 1) AS sent,
                     (SELECT COUNT(*) FROM messages w WHERE w.conversation = c.id AND w.from_writer = 1 AND w.checked = '${SentCheck.Corrected.token}') AS corrected
                   FROM messages m JOIN conversations c ON c.id = m.conversation
                   WHERE m.session_id IN ($marks)""",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val conversation = cursor.getString(1)
                    out[cursor.getString(0)] = byConversation.getOrPut(conversation) {
                        ChatThreadInfo(conversation, cursor.getString(2), cursor.stringOrNull("persona"), cursor.getString(4),
                            cursor.getInt(5), cursor.getInt(6))
                    }
                }
            }
        }
        out
    }

    /**
     * Deletes [id] and its messages and durably queues their History/memory cleanup in the same
     * transaction. True only when a conversation was removed and cleanup was queued.
     */
    suspend fun delete(id: String, permitted: () -> Boolean = { true }): Boolean = io("delete a conversation", false) { db ->
        db.transaction {
            val exists = db.rawQuery("SELECT id FROM conversations WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
            if (!exists) return@transaction false
            // Read links directly: an unreadable message must not lose its History cleanup ID.
            val sessions = db.rawQuery(
                "SELECT DISTINCT session_id FROM messages WHERE conversation = ? AND session_id IS NOT NULL ORDER BY session_id",
                arrayOf(id),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (!permitted()) return@transaction false
            db.delete(CONVERSATIONS, "id = ?", arrayOf(id))
            db.insertOrThrow(DELETIONS, null, ContentValues().apply {
                put("conversation", id)
                put("session_ids", json.encodeToString(sessionList, sessions))
            })
            true
        }
    }

    /**
     * Rewinds [conversationId] to just before the writer's message [messageId], to edit it:
     * that message and every message after it go, and their History, time and memory cleanup
     * is queued durably in the same transaction (drained by `ChatDeletions`, like a deleted
     * conversation's). Memory will learn the conversation again from what remains. Returns
     * what remains; null when nothing was removed (no such writer message, not [permitted],
     * or the write failed).
     */
    suspend fun rewind(conversationId: String, messageId: String, permitted: () -> Boolean = { true }): ChatSnapshot? =
        io("rewind a conversation", null) { db ->
            db.transaction {
                val anchor = db.rawQuery(
                    "SELECT at, seq FROM messages WHERE id = ? AND conversation = ? AND from_writer = 1",
                    arrayOf(messageId, conversationId),
                ).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null } ?: return@transaction null
                val (at, seq) = anchor
                // The order the thread is read in: (at, seq).
                val from = "conversation = ? AND (at > ? OR (at = ? AND seq >= ?))"
                val args = arrayOf(conversationId, at.toString(), at.toString(), seq.toString())
                // Read links directly: an unreadable message must not lose its History cleanup ID.
                val sessions = db.rawQuery(
                    "SELECT DISTINCT session_id FROM messages WHERE $from AND session_id IS NOT NULL ORDER BY session_id", args,
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                if (!permitted()) return@transaction null
                db.delete(MESSAGES, from, args)
                db.update(CONVERSATIONS, ContentValues().apply { put("learned_count", 0) }, "id = ?", arrayOf(conversationId))
                db.insertOrThrow(REWINDS, null, ContentValues().apply {
                    put("message", messageId)
                    put("conversation", conversationId)
                    put("session_ids", json.encodeToString(sessionList, sessions))
                })
                checkNotNull(db.snapshot(conversationId))
            }
        }

    /** Database failures throw; malformed entries stay pending and are explicitly reported. */
    suspend fun pendingDeletions(): PendingChatDeletions = withContext(dispatcher) {
        val db = helper.readableDatabase
        var unreadable = false
        fun decode(encoded: String): List<String>? = try {
            json.decodeFromString(sessionList, encoded)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unreadable conversation cleanup remains pending", e)
            unreadable = true
            null
        }
        val entries = buildList {
            db.rawQuery("SELECT conversation, session_ids FROM $DELETIONS ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) {
                    decode(cursor.getString(1))?.let { add(PendingChatDeletion(cursor.getString(0), it)) }
                }
            }
            db.rawQuery("SELECT conversation, session_ids, message FROM $REWINDS ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) {
                    decode(cursor.getString(1))?.let { add(PendingChatDeletion(cursor.getString(0), it, rewoundFrom = cursor.getString(2))) }
                }
            }
        }
        PendingChatDeletions(entries, unreadable)
    }

    /** Retires only the exact work that was acknowledged, never a replacement for the same ID. */
    suspend fun completeDeletion(deletion: PendingChatDeletion): Boolean = withContext(dispatcher) {
        val sessions = json.encodeToString(sessionList, deletion.sessionIds)
        val rewound = deletion.rewoundFrom
        if (rewound != null) {
            helper.writableDatabase.delete(REWINDS, "message = ? AND conversation = ? AND session_ids = ?",
                arrayOf(rewound, deletion.conversationId, sessions)) == 1
        } else {
            helper.writableDatabase.delete(DELETIONS, "conversation = ? AND session_ids = ?",
                arrayOf(deletion.conversationId, sessions)) == 1
        }
    }

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

    /** Closes the database; for tests. */
    fun close() = helper.close()

    @Serializable
    private data class StoredChange(val kind: String, val start: Int, val end: Int, val from: String, val to: String, val why: String? = null)

    private fun SQLiteDatabase.messages(id: String): List<ChatMessage> =
        rawQuery("SELECT * FROM messages WHERE conversation = ? ORDER BY at, seq", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.message()?.let(::add) }
        }

    private fun Cursor.conversation() = Conversation(
        id = string("id"),
        title = string("title"),
        language = string("language"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        titleIsCustom = int("title_custom") != 0,
        learnedCount = int("learned_count"),
        persona = stringOrNull("persona"),
    )

    /** Null for a row this build can't read, which is skipped rather than failing the list. */
    private fun Cursor.message(): ChatMessage? = runCatching {
        val changes = stringOrNull("changes")?.let { json.decodeFromString(changeList, it) }.orEmpty().mapNotNull { stored ->
            EditKind.tokens.decode(stored.kind)?.let { ChatChange(it, stored.start, stored.end, stored.from, stored.to, stored.why) }
        }
        ChatMessage(
            id = string("id"),
            conversationId = string("conversation"),
            fromWriter = int("from_writer") != 0,
            raw = string("raw"),
            text = string("text"),
            changes = changes,
            check = SentCheck.read(stringOrNull("checked")),
            at = long("at"),
            sessionId = stringOrNull("session_id"),
            translation = stringOrNull("translation"),
        )
    }.onFailure { Log.w(TAG, "Skipped an unreadable message", it) }.getOrNull()

    private fun Conversation.values() = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("language", language)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("title_custom", if (titleIsCustom) 1 else 0)
        put("learned_count", learnedCount)
        put("persona", persona)
    }

    private fun ChatMessage.values() = ContentValues().apply {
        put("id", id)
        put("conversation", conversationId)
        put("from_writer", if (fromWriter) 1 else 0)
        put("raw", raw)
        put("text", text)
        put("changes", json.encodeToString(changeList, changes.map { StoredChange(it.kind.token, it.start, it.end, it.from, it.to, it.why) }))
        put("checked", check.token)
        put("at", at)
        put("session_id", sessionId)
        put("translation", translation)
    }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    language TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    title_custom INTEGER NOT NULL DEFAULT 0,
                    learned_count INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL(
                """CREATE TABLE messages (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE,
                    conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    from_writer INTEGER NOT NULL,
                    raw TEXT NOT NULL,
                    text TEXT NOT NULL,
                    changes TEXT,
                    checked TEXT NOT NULL DEFAULT 'none',
                    at INTEGER NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX messages_by_conversation ON messages(conversation, at)")
            upgradeTo2(db)
            upgradeTo3(db)
            upgradeTo4(db)
            upgradeTo5(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) upgradeTo2(db)
            if (oldVersion < 3) upgradeTo3(db)
            if (oldVersion < 4) upgradeTo4(db)
            if (oldVersion < 5) upgradeTo5(db)
        }

        /**
         * Version 5: cleanup after rewinding a conversation to edit a message, one row per
         * edited message. Apart from deletions, since the conversation itself stays.
         */
        private fun upgradeTo5(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $REWINDS (message TEXT PRIMARY KEY NOT NULL, conversation TEXT NOT NULL, session_ids TEXT NOT NULL)")
        }

        /** Version 4: cleanup that must outlive the conversation and the current process. */
        private fun upgradeTo4(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $DELETIONS (conversation TEXT PRIMARY KEY NOT NULL, session_ids TEXT NOT NULL)")
        }

        /** Version 3: a partner message's translation, written with it. Older replies have none. */
        private fun upgradeTo3(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translation TEXT")
        }

        /** Version 2: who the partner plays, and the History session each message was saved as. */
        private fun upgradeTo2(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN persona TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN session_id TEXT")
            db.execSQL("CREATE INDEX messages_by_session ON messages(session_id)")
        }
    }

    companion object {
        /** The database file's name. */
        const val DATABASE = "lab_chat"
        private const val VERSION = 5

        /** SQLite's default limit on bound arguments is 999. */
        private const val SQL_ARGS = 500
        private const val CONVERSATIONS = "conversations"
        private const val MESSAGES = "messages"
        private const val DELETIONS = "pending_deletions"
        private const val REWINDS = "pending_rewinds"
        private const val TAG = "LabChat"
        private val json = Json { ignoreUnknownKeys = true }
        private val changeList = ListSerializer(StoredChange.serializer())
        private val sessionList = ListSerializer(String.serializer())
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
private fun Cursor.stringOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
