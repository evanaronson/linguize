package com.evanaronson.linguize.data.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lab Chat, practice targets: what a reply aimed at is kept with it (version 7), for checking the steering only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AimingAtStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context)

    @After fun close() = store.close()

    @Test fun aRepliesAimIsStoredWithItAndOfferedToTheNextConversationInItsLanguage() = runBlocking {
        store.create(Conversation("c1", "", "ca", 10, 10))
        store.create(Conversation("c2", "", "es", 20, 20))
        store.create(Conversation("c3", "", "ca", 30, 30))
        store.add(ChatMessage.partner("p1", "c1", "Què faràs?", 11, "What will you do?", aimingAt = "future, I"))
        store.add(ChatMessage.partner("p2", "c1", "I demà?", 12, aimingAt = "future, we"))
        store.add(ChatMessage.partner("p3", "c1", "Hola", 13, aimingAt = "  "))
        store.add(ChatMessage.partner("e1", "c2", "¿Y tú?", 21, aimingAt = "playa"))
        assertEquals(listOf("future, I", "future, we", null), store.messages("c1").map { it.aimingAt })
        assertEquals(listOf("future, we", "future, I"), store.recentAims("ca", excluding = "c3"))
        // Not the conversation itself, and never another language's.
        assertTrue(store.recentAims("ca", excluding = "c1").isEmpty())
        assertEquals(listOf("playa"), store.recentAims("es", excluding = null))
    }

    @Test fun aVersionSixDatabaseGainsTheColumnWithoutBackfill() = runBlocking {
        store.close()
        context.deleteDatabase(ChatStore.DATABASE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatStore.DATABASE), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', language TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, title_custom INTEGER NOT NULL DEFAULT 0, learned_count INTEGER NOT NULL DEFAULT 0, persona TEXT)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, from_writer INTEGER NOT NULL, raw TEXT NOT NULL, text TEXT NOT NULL, changes TEXT, checked TEXT NOT NULL DEFAULT 'none', at INTEGER NOT NULL, session_id TEXT, translation TEXT)")
            db.execSQL("CREATE TABLE pending_deletions (conversation TEXT PRIMARY KEY NOT NULL, session_ids TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending_rewinds (message TEXT PRIMARY KEY NOT NULL, conversation TEXT NOT NULL, session_ids TEXT NOT NULL)")
            db.execSQL("CREATE TABLE reply_reads (message TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE, conversation TEXT NOT NULL, language TEXT NOT NULL, at INTEGER NOT NULL, needed_at INTEGER)")
            db.execSQL("INSERT INTO conversations VALUES ('c', '', 'es', 1, 2, 0, 0, NULL)")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at, translation) VALUES ('old', 'c', 0, 'Hola', 'Hola', '[]', 'none', 2, 'Hi')")
            db.version = 6
        }
        val upgraded = ChatStore(context)
        assertNull(upgraded.messages("c").single().aimingAt)
        upgraded.add(ChatMessage.partner("new", "c", "¿Qué tal?", 3, aimingAt = "preterite, I"))
        assertEquals(listOf(null, "preterite, I"), upgraded.messages("c").map { it.aimingAt })
        upgraded.close()
    }
}
