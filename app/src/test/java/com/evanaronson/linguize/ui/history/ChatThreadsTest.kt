package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab Chat in History: a conversation is one row, so it never buries the checks sent to people. */
class ChatThreadsTest {
    private fun row(id: String, at: Long, origin: Origin) =
        RecentRow(SessionSummary(id, at, origin, null, "text $id", Outcome.Applied, 0, 0, 0), null)

    private val weekend = ChatThreadInfo("a", "Weekend plans", null, "es", messages = 8, corrected = 3)
    private val landlord = ChatThreadInfo("b", "Jordi", "Jordi, my landlord", "ca", messages = 3, corrected = 1)

    // Newest first, as History reads them.
    private val rows = (1..8).map { row("a$it", 100L - it, Origin.Chat) }.take(4) +
        row("menu1", 95, Origin.Menu) +
        (5..8).map { row("a$it", 100L - it, Origin.Chat) } +
        (1..3).map { row("b$it", 80L - it, Origin.Chat) } +
        row("menu2", 70, Origin.Button) + row("menu3", 60, Origin.Menu) + row("menu4", 50, Origin.Menu)
    private val threads = (1..8).associate { "a$it" to weekend } + (1..3).associate { "b$it" to landlord }

    @Test fun eachConversationIsOneRowWhereItsNewestMessageIs() {
        val grouped = groupChatThreads(rows, threads)
        assertEquals(listOf("chat:a", "menu1", "chat:b", "menu2", "menu3", "menu4"), grouped.map { it.key })
        assertEquals(weekend, grouped.first().thread)
        assertEquals("a1", grouped.first().summary.id)
    }

    @Test fun homesFiveRowsCountAConversationAsOne() {
        val five = groupChatThreads(rows, threads).take(HistoryViewModel.PREVIEW_SIZE)
        assertEquals(5, five.size)
        assertEquals(listOf("chat:a", "menu1", "chat:b", "menu2", "menu3"), five.map { it.key })
        assertTrue(HistoryViewModel.PREVIEW_WINDOW > rows.size)
    }

    @Test fun onlyChatRowsAreLookedUpAndUnknownOnesStayAsChecks() {
        assertEquals((1..8).map { "a$it" } + (1..3).map { "b$it" }, chatSessionIds(rows).sortedBy { it })
        val withoutThreads = groupChatThreads(rows, emptyMap())
        assertEquals(rows, withoutThreads)
    }
}
