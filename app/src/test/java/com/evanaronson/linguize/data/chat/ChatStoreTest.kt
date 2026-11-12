package com.evanaronson.linguize.data.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SentCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Conversations and messages in the `lab_chat` database, including upgrades from older versions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context)

    @After fun close() = store.close()

    private val first = Conversation("c1", "", "es", createdAt = 10, updatedAt = 10)
    private val second = Conversation("c2", "", "ca", createdAt = 20, updatedAt = 20)
    private val opener = ChatMessage.partner("p1", "c1", "¡Hola! ¿Qué tal?", 11)
    private val mine = ChatMessage(
        "w1", "c1", fromWriter = true, raw = "Muy bien, tengo frio", text = "Muy bien, tengo frío",
        changes = listOf(ChatChange(EditKind.Fix, 16, 20, "frio", "frío", "Accent")), check = SentCheck.Corrected, at = 30,
    )

    @Test fun keepsMessagesInOrderWithTheirRecord() = runBlocking {
        assertTrue(store.create(first))
        assertTrue(store.add(opener))
        assertTrue(store.add(mine))
        assertEquals(listOf(opener, mine), store.messages("c1"))
        assertEquals(30L, store.conversation("c1")?.updatedAt)
    }

    @Test fun listsConversationsMostRecentFirstWithTheirLastMessage() = runBlocking {
        store.create(first)
        store.create(second)
        store.add(opener)
        store.add(mine)
        val list = store.conversations()
        assertEquals(listOf("c1", "c2"), list.map { it.conversation.id })
        assertEquals("Muy bien, tengo frío", list[0].lastText)
        assertEquals(1, list[0].writerMessages)
        assertNull(list[1].lastText)
        assertEquals(0, list[1].writerMessages)
    }

    @Test fun renamesAndNotesWhatMemoryLearned() = runBlocking {
        store.create(first)
        store.rename("c1", "Tengo frío", custom = true)
        store.setLearned("c1", 2)
        val stored = store.conversation("c1")!!
        assertEquals("Tengo frío", stored.title)
        assertTrue(stored.titleIsCustom)
        assertEquals(first.language, stored.language)
        assertEquals(2, stored.learnedCount)
        assertFalse(store.rename("missing", "x", custom = true))
    }

    @Test fun partnerAppendRequiresTheExpectedTailAndPreservesAnExistingGeneratedTitle() = runBlocking {
        store.create(first.copy(title = "Already named"))
        store.add(opener)
        store.add(mine)
        val reply = ChatMessage.partner("p2", "c1", "A current reply", 40)
        assertEquals(PartnerAppend.Superseded, store.appendPartner(reply, opener.id, "Stale title") { true })
        assertEquals(listOf(opener, mine), store.messages("c1"))
        val saved = store.appendPartner(reply, mine.id, "Another generated title") { true } as PartnerAppend.Saved
        assertEquals(listOf(opener, mine, reply), saved.snapshot.messages)
        assertEquals("Already named", saved.snapshot.conversation.title)
        assertFalse(saved.snapshot.conversation.titleIsCustom)
        assertEquals(saved.snapshot, store.readThread("c1"))
    }

    @Test fun strictThreadReadRetainsTheLegacyUnreadableMessagePolicy() = runBlocking {
        store.create(first)
        store.add(opener)
        store.add(mine)
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(ChatStore.DATABASE).path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE messages SET changes = 'not-json' WHERE id = ?", arrayOf(opener.id))
        }
        val loaded = store.readThread("c1")!!
        assertEquals("c1", loaded.conversation.id)
        assertEquals(listOf(mine), loaded.messages)
        assertEquals(store.messages("c1"), loaded.messages)
    }

    @Test fun deleteRemovesTheConversationAndItsMessages() = runBlocking {
        store.create(first)
        store.add(opener)
        store.add(mine)
        val before = store.readThread("c1")!!
        assertEquals(first.copy(updatedAt = 30), before.conversation)
        assertEquals(listOf(opener, mine), before.messages)
        assertTrue(store.delete("c1"))
        assertTrue(store.conversations().isEmpty())
        assertTrue(store.messages("c1").isEmpty())
        assertFalse(store.delete("c1"))
    }

    @Test fun notesEachMessagesSavedCheckAndGroupsThemByConversation() = runBlocking {
        store.create(first)
        store.create(second.copy(persona = "Jordi, my landlord, formal", title = "Jordi"))
        store.add(opener)
        store.add(mine.copy(sessionId = "s1"))
        store.add(ChatMessage("w2", "c1", true, "Vale", "Vale", check = SentCheck.LooksGood, at = 40, sessionId = "s2"))
        store.add(ChatMessage("w3", "c2", true, "Bon dia", "Bon dia", check = SentCheck.LooksGood, at = 50, sessionId = "s3"))
        assertEquals("s1", store.messages("c1").single { it.id == "w1" }.sessionId)
        assertEquals(setOf("s1", "s2"), store.sessionIds("c1").toSet())

        val threads = store.threads(listOf("s1", "s2", "s3", "elsewhere"))
        assertEquals(setOf("s1", "s2", "s3"), threads.keys)
        val chat = threads.getValue("s1")
        assertEquals("c1", chat.conversationId)
        assertEquals(2, chat.messages)
        assertEquals(1, chat.corrected)
        assertEquals(chat, threads.getValue("s2"))
        assertEquals("Jordi, my landlord, formal", threads.getValue("s3").persona)
        assertEquals("Jordi, my landlord, formal", store.conversation("c2")?.persona)
        assertTrue(store.threads(emptyList()).isEmpty())
    }

    @Test fun aVersionOneDatabaseGainsPersonaAndSessionColumns() = runBlocking {
        store.close()
        context.deleteDatabase(ChatStore.DATABASE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatStore.DATABASE), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', language TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, title_custom INTEGER NOT NULL DEFAULT 0, learned_count INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, from_writer INTEGER NOT NULL, raw TEXT NOT NULL, text TEXT NOT NULL, changes TEXT, checked TEXT NOT NULL DEFAULT 'none', at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO conversations VALUES ('old', 'Old', 'es', 1, 2, 0, 0)")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at) VALUES ('m', 'old', 1, 'Hola', 'Hola', '[]', 'none', 2)")
            db.version = 1
        }
        val upgraded = ChatStore(context)
        assertEquals("Old", upgraded.conversation("old")?.title)
        assertNull(upgraded.conversation("old")?.persona)
        assertNull(upgraded.messages("old").single().sessionId)
        assertNull(upgraded.messages("old").single().translation)
        upgraded.add(ChatMessage.partner("p", "old", "¿Qué tal?", 3, translation = "How are you?"))
        assertEquals("How are you?", upgraded.messages("old").last().translation)
        assertTrue(upgraded.delete("old"))
        assertNull(upgraded.conversation("old"))
        assertTrue(upgraded.messages("old").isEmpty())
        upgraded.close()
    }

    @Test fun aVersionTwoDatabaseGainsTheTranslationColumn() = runBlocking {
        store.close()
        context.deleteDatabase(ChatStore.DATABASE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatStore.DATABASE), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', language TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, title_custom INTEGER NOT NULL DEFAULT 0, learned_count INTEGER NOT NULL DEFAULT 0, persona TEXT)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, from_writer INTEGER NOT NULL, raw TEXT NOT NULL, text TEXT NOT NULL, changes TEXT, checked TEXT NOT NULL DEFAULT 'none', at INTEGER NOT NULL, session_id TEXT)")
            db.execSQL("INSERT INTO conversations VALUES ('c', 'Jordi', 'ca', 1, 2, 0, 0, 'Jordi')")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at, session_id) VALUES ('p', 'c', 0, 'Bon dia', 'Bon dia', '[]', 'none', 2, NULL)")
            db.version = 2
        }
        val upgraded = ChatStore(context)
        val old = upgraded.messages("c").single()
        assertEquals("Bon dia", old.text)
        // An older reply has no translation of its own: it is fetched only if tapped.
        assertNull(old.translation)
        assertEquals("Jordi", upgraded.conversation("c")?.persona)
        upgraded.close()
    }

    @Test fun rewindRemovesTheWritersMessageAndEverythingAfterItAndQueuesTheirCleanup() = runBlocking {
        assertTrue(store.create(first.copy(learnedCount = 4)))
        assertTrue(store.create(second))
        store.add(opener)
        store.add(mine.copy(sessionId = "s1"))
        store.add(ChatMessage.partner("p2", "c1", "¡Abrígate!", 31))
        // Same time as the previous reply: order is (at, seq), as the thread reads it.
        store.add(ChatMessage("w2", "c1", true, "Vale", "Vale", check = SentCheck.LooksGood, at = 31, sessionId = "s2"))
        store.add(ChatMessage.partner("p3", "c1", "Hasta luego", 32))
        store.add(ChatMessage("w3", "c2", true, "Hola", "Hola", at = 40, sessionId = "s3"))

        // A partner message, or another conversation's message, is not rewound to.
        assertNull(store.rewind("c1", "p2"))
        assertNull(store.rewind("c2", "w2"))
        assertTrue(store.pendingDeletions().entries.isEmpty())

        val left = store.rewind("c1", "w2")!!
        assertEquals(listOf("p1", "w1", "p2"), left.messages.map { it.id })
        assertEquals(listOf("p1", "w1", "p2"), store.messages("c1").map { it.id })
        assertEquals(0, store.conversation("c1")!!.learnedCount)
        assertEquals(listOf(PendingChatDeletion("c1", listOf("s2"), rewoundFrom = "w2")), store.pendingDeletions().entries)

        assertNotNull(store.rewind("c1", "w1"))
        assertEquals(listOf("p1"), store.messages("c1").map { it.id })
        val pending = store.pendingDeletions().entries
        assertEquals(listOf(listOf("s2"), listOf("s1")), pending.map { it.sessionIds })
        // The conversation, and the other one, stay.
        assertEquals("c1", store.conversation("c1")?.id)
        assertEquals(listOf("s3"), store.sessionIds("c2"))

        assertTrue(store.completeDeletion(pending.first()))
        assertEquals(listOf("w1"), store.pendingDeletions().entries.map { it.rewoundFrom })
        // A conversation deletion and a rewind of it are separate work.
        assertTrue(store.delete("c1"))
        assertEquals(listOf(null, "w1"), store.pendingDeletions().entries.map { it.rewoundFrom })
    }

    @Test fun aVersionFourDatabaseGainsTheRewindQueue() = runBlocking {
        store.close()
        context.deleteDatabase(ChatStore.DATABASE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatStore.DATABASE), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', language TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, title_custom INTEGER NOT NULL DEFAULT 0, learned_count INTEGER NOT NULL DEFAULT 0, persona TEXT)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, from_writer INTEGER NOT NULL, raw TEXT NOT NULL, text TEXT NOT NULL, changes TEXT, checked TEXT NOT NULL DEFAULT 'none', at INTEGER NOT NULL, session_id TEXT, translation TEXT)")
            db.execSQL("CREATE TABLE pending_deletions (conversation TEXT PRIMARY KEY NOT NULL, session_ids TEXT NOT NULL)")
            db.execSQL("INSERT INTO conversations VALUES ('c', '', 'es', 1, 2, 0, 2, NULL)")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at, session_id) VALUES ('p', 'c', 0, 'Hola', 'Hola', '[]', 'none', 2, NULL)")
            db.execSQL("INSERT INTO messages (id, conversation, from_writer, raw, text, changes, checked, at, session_id) VALUES ('w', 'c', 1, 'Bien', 'Bien', '[]', 'looks_good', 3, 's')")
            db.execSQL("INSERT INTO pending_deletions VALUES ('gone', '[\"x\"]')")
            db.version = 4
        }
        val upgraded = ChatStore(context)
        assertEquals(listOf(PendingChatDeletion("gone", listOf("x"))), upgraded.pendingDeletions().entries)
        assertEquals(listOf("p"), upgraded.rewind("c", "w")!!.messages.map { it.id })
        assertEquals(listOf(null, "w"), upgraded.pendingDeletions().entries.map { it.rewoundFrom })
        upgraded.close()
    }
}
