package com.evanaronson.linguize.data.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.corpus.Span
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Conversations and messages in the `lab_chat` database, and the corpus read from them. */
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

    @Test fun renamesChangesLanguageAndNotesWhatMemoryLearned() = runBlocking {
        store.create(first)
        store.rename("c1", "Tengo frío", custom = true)
        store.setLanguage("c1", "ca")
        store.setLearned("c1", 2)
        val stored = store.conversation("c1")!!
        assertEquals("Tengo frío", stored.title)
        assertTrue(stored.titleIsCustom)
        assertEquals("ca", stored.language)
        assertEquals(2, stored.learnedCount)
        assertFalse(store.rename("missing", "x", custom = true))
    }

    @Test fun deleteRemovesEverythingAndUndoBringsItBack() = runBlocking {
        store.create(first)
        store.add(opener)
        store.add(mine)
        val removed = store.delete("c1")!!
        assertEquals(first.copy(updatedAt = 30), removed.first)
        assertEquals(listOf(opener, mine), removed.second)
        assertTrue(store.conversations().isEmpty())
        assertTrue(store.messages("c1").isEmpty())
        assertTrue(ChatCorpusSource(store).utterances().isEmpty())
        assertNull(store.delete("c1"))

        store.restore(removed.first, removed.second)
        assertEquals(listOf(opener, mine), store.messages("c1"))
    }

    @Test fun replacingMessagesSwapsAnUnansweredOpener() = runBlocking {
        store.create(first)
        store.add(opener)
        val catalan = ChatMessage.partner("p2", "c1", "Hola! Què tal?", 12)
        store.replaceMessages("c1", listOf(catalan))
        assertEquals(listOf(catalan), store.messages("c1"))
    }

    @Test fun theCorpusSourceReadsRawWriterTextOnly() = runBlocking {
        store.create(first)
        store.add(opener)
        store.add(mine)
        val source = ChatCorpusSource(store)
        assertEquals("chat", source.name)
        val utterances = source.utterances()
        assertEquals(1, utterances.size)
        assertEquals("chat:w1", utterances[0].id)
        assertEquals("Muy bien, tengo frio", utterances[0].text)
        assertEquals("es", utterances[0].language)
        assertEquals(listOf(Span(16, 20)), utterances[0].corrected)
    }
}
