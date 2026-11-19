package com.evanaronson.linguize.data.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ReplyRead
import com.evanaronson.linguize.core.chat.SentCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lab, Translation reliance: measured replies live with their messages in `lab_chat`, and go with them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReplyReadsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context)

    @After fun close() = store.close()

    private val spanish = Conversation("c1", "", "es", createdAt = 10, updatedAt = 10)
    private val catalan = Conversation("c2", "", "ca", createdAt = 20, updatedAt = 20)
    private fun reply(id: String, conversation: String, at: Long, translation: String? = "Hi") =
        ChatMessage.partner(id, conversation, "Hola", at, translation)
    private fun mine(id: String, conversation: String, at: Long) =
        ChatMessage(id, conversation, fromWriter = true, raw = "Bien", text = "Bien", check = SentCheck.LooksGood, at = at, sessionId = "s$id")

    @Test fun repliesWithATranslationAreMeasuredInTheirConversationsLanguage() = runBlocking {
        store.create(spanish)
        store.create(catalan)
        store.add(reply("p1", "c1", 11))
        store.add(mine("w1", "c1", 12))
        // A reply without its own translation (a fallback opener) can't be measured.
        store.add(reply("p2", "c1", 13, translation = null))
        assertTrue(store.appendPartner(reply("p3", "c2", 21), afterMessageId = null, title = null) { true } is PartnerAppend.Saved)
        store.add(mine("w3", "c2", 22))
        assertEquals(listOf(ReplyRead("p1", "es", 11), ReplyRead("p3", "ca", 21)), store.replyReads())
    }

    @Test fun aReplyIsMarkedNeededOnce() = runBlocking {
        store.create(spanish)
        store.add(reply("p1", "c1", 11))
        assertTrue(store.markTranslationNeeded("p1", 500))
        assertFalse(store.markTranslationNeeded("p1", 900))
        store.add(mine("w1", "c1", 12))
        assertEquals(listOf(ReplyRead("p1", "es", 11, neededAt = 500)), store.replyReads())
        // A writer message, or a reply from before this version, has nothing to mark: no backfill.
        assertFalse(store.markTranslationNeeded("w1", 900))
        assertFalse(store.markTranslationNeeded("unknown", 900))
        assertEquals(1, store.replyReads().size)
    }

    @Test fun deletingAConversationDeletesItsMeasurements() = runBlocking {
        store.create(spanish)
        store.create(catalan)
        store.add(reply("p1", "c1", 11))
        store.markTranslationNeeded("p1", 500)
        store.add(mine("w1", "c1", 12))
        store.add(reply("p2", "c2", 21))
        store.add(mine("w2", "c2", 22))
        assertTrue(store.delete("c1"))
        assertEquals(listOf("p2"), store.replyReads().map { it.messageId })
    }

    @Test fun rewindingToEditAMessageDeletesTheMeasurementsOfTheRepliesItRemoves() = runBlocking {
        store.create(spanish)
        store.add(reply("p1", "c1", 11))
        store.add(mine("w1", "c1", 12))
        store.add(reply("p2", "c1", 13))
        store.add(mine("w2", "c1", 14))
        store.add(reply("p3", "c1", 15))
        store.markTranslationNeeded("p1", 100)
        store.markTranslationNeeded("p3", 100)
        store.rewind("c1", "w2")!!
        assertEquals(listOf(ReplyRead("p1", "es", 11, 100), ReplyRead("p2", "es", 13)), store.replyReads())
        // p2 is unanswered again until the edited message is sent, so it doesn't count yet.
        assertEquals(listOf("p1"), store.replyReads(answeredOnly = true).map { it.messageId })
        store.rewind("c1", "w1")!!
        assertEquals(listOf("p1"), store.replyReads().map { it.messageId })
    }

    @Test fun aVersionFiveDatabaseGainsTheTableWithoutBackfill() = runBlocking {
        store.close()
        context.deleteDatabase(ChatStore.DATABASE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatStore.DATABASE), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', language TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, title_custom INTEGER NOT NULL DEFAULT 0, learned_count INTEGER NOT NULL DEFAULT 0, persona TEXT)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, from_writer INTEGER NOT NULL, raw TEXT NOT NULL, text TEXT NOT NULL, changes TEXT, checked TEXT NOT NULL DEFAULT 'none', at INTEGER NOT NULL, session_id TEXT, translation TEXT)")
            db.execSQL("CREATE TABLE pending_deletions (conversation TEXT PRIMARY KEY NOT NULL, session_ids TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending_rewinds (message TEXT PRIMARY KEY NOT NULL, conversation TEXT NOT NULL, session_ids TEXT NOT NULL)")
            db.execSQL("INSERT INTO conversations VALUES ('c', '', 'es', 1, 2, 0, 0, NULL)")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at, translation) VALUES ('old', 'c', 0, 'Hola', 'Hola', '[]', 'none', 2, 'Hi')")
            db.version = 5
        }
        val upgraded = ChatStore(context)
        assertTrue(upgraded.replyReads().isEmpty())
        assertFalse(upgraded.markTranslationNeeded("old", 10))
        upgraded.add(reply("new", "c", 3))
        assertEquals(listOf(ReplyRead("new", "es", 3)), upgraded.replyReads())
        assertTrue(upgraded.delete("c"))
        assertTrue(upgraded.replyReads().isEmpty())
        upgraded.close()
    }

    @Test fun onlyRepliesTheWriterAnsweredCount() = runBlocking {
        store.create(spanish)
        store.add(reply("p1", "c1", 11))
        store.markTranslationNeeded("p1", 50)
        // Not answered yet: whether its translation was needed says nothing yet.
        assertEquals(emptyList<ReplyRead>(), store.replyReads(answeredOnly = true))
        store.add(mine("w1", "c1", 12))
        store.add(reply("p2", "c1", 13))
        assertEquals(listOf(ReplyRead("p1", "es", 11, 50)), store.replyReads(answeredOnly = true))
        store.add(mine("w2", "c1", 14))
        assertEquals(listOf("p1", "p2"), store.replyReads(answeredOnly = true).map { it.messageId })
    }
}
