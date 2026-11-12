package com.evanaronson.linguize.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which conversation the app opens to, its language, and when memory learns. */
class ChatStartTest {
    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour
    private fun summary(updatedAt: Long, writerMessages: Int, learned: Int = 0) = ConversationSummary(
        Conversation("c", "", "es", createdAt = 0, updatedAt = updatedAt, learnedCount = learned), "hola", writerMessages,
    )

    @Test fun noConversationsStartsANewOne() {
        assertEquals(ChatStart.Opening.New, ChatStart.opening(null, now))
    }

    @Test fun aRecentConversationIsResumed() {
        assertEquals(ChatStart.Opening.Resume("c"), ChatStart.opening(summary(now - hour, writerMessages = 3), now))
    }

    @Test fun aQuietConversationIsOverAndANewOneStarts() {
        assertEquals(ChatStart.Opening.New, ChatStart.opening(summary(now - 7 * hour, writerMessages = 3), now))
    }

    @Test fun anUnansweredOpenerIsReusedHoweverOld() {
        assertEquals(ChatStart.Opening.Resume("c"), ChatStart.opening(summary(now - 48 * hour, writerMessages = 0), now))
    }

    private val supported = listOf("ca", "es")

    @Test fun theLastLanguageChosenWins() {
        assertEquals("ca", ChatStart.language("ca", listOf("es"), listOf("es"), supported))
    }

    @Test fun thenTheOnlyLanguageTheMenuNames() {
        assertEquals("ca", ChatStart.language(null, listOf("ca"), listOf("es"), supported))
        // Both named, or none: the menu says nothing.
        assertEquals("es", ChatStart.language(null, listOf("ca", "es"), listOf("es", "ca"), supported))
    }

    @Test fun thenTheMostCheckedLanguageInHistoryThenSpanish() {
        assertEquals("ca", ChatStart.language(null, emptyList(), ChatStart.mostUsed(listOf("ca", null, "es", "ca")), supported))
        assertEquals("es", ChatStart.language("fr", emptyList(), emptyList(), supported))
        assertEquals(listOf("ca", "es"), ChatStart.mostUsed(listOf("es", "ca", null, "ca")))
    }

    @Test fun memoryLearnsOnceQuietOrLeftAndOnlyFromNewMessages() {
        val quiet = Conversation("c", "", "es", 0, updatedAt = now - hour)
        val active = quiet.copy(updatedAt = now - 60_000)
        assertTrue(ChatStart.shouldLearn(quiet, messages = 4, writerMessages = 2, now = now, left = false))
        assertFalse(ChatStart.shouldLearn(active, messages = 4, writerMessages = 2, now = now, left = false))
        assertTrue(ChatStart.shouldLearn(active, messages = 4, writerMessages = 2, now = now, left = true))
        assertFalse(ChatStart.shouldLearn(quiet.copy(learnedCount = 4), messages = 4, writerMessages = 2, now = now, left = true))
        assertFalse(ChatStart.shouldLearn(quiet, messages = 1, writerMessages = 0, now = now, left = true))
    }
}
