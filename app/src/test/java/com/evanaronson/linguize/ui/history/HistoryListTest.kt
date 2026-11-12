package com.evanaronson.linguize.ui.history

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** History is one list of messages: one row each, under day headers, with a source filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rGB")
class HistoryListTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val zone = ZoneId.of("UTC")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun close() = scope.cancel()

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun summary(id: String, at: Long, origin: Origin, app: String? = null) =
        SessionSummary(id, at, origin, app, "text $id", Outcome.Applied, 1, 0, 1)
    private fun row(id: String, at: Long, origin: Origin, app: String? = null, label: String? = null, chat: ChatThreadInfo? = null) =
        RecentRow(summary(id, at, origin, app), label, chat)

    private val weekend = ChatThreadInfo("a", "Weekend plans", null, "es", messages = 2, corrected = 1)
    private val whatsapp = "com.whatsapp"
    private val messages = "com.google.android.apps.messaging"

    // Newest first, as History reads them: chat and app messages interleaved over three days.
    private val rows = listOf(
        row("chat1", at(8, 18), Origin.Chat, chat = weekend),
        row("wa1", at(8, 17), Origin.Menu, whatsapp, "WhatsApp"),
        row("chat2", at(8, 9), Origin.Chat, chat = weekend),
        row("sms1", at(7, 20), Origin.Button, messages, "Messages"),
        row("wa2", at(5, 12), Origin.Menu, whatsapp, "WhatsApp"),
        row("unknown", at(5, 11), Origin.Menu),
    )

    @Test fun chatAndAppMessagesAreIndividualRowsInTimeOrder() {
        val items = historyItems(rows, zone)
        val messagesShown = items.filterIsInstance<HistoryItem.Message>().map { it.row.summary.id }
        assertEquals(listOf("chat1", "wa1", "chat2", "sms1", "wa2", "unknown"), messagesShown)
        assertEquals(rows.map { it.summary.id }, rows.map { it.key })
    }

    @Test fun eachDayHasOneHeaderBeforeItsMessages() {
        val items = historyItems(rows, zone)
        assertEquals(
            listOf("day:2026-11-08", "chat1", "wa1", "chat2", "day:2026-11-07", "sms1", "day:2026-11-05", "wa2", "unknown"),
            items.map { it.key },
        )
    }

    @Test fun headersFollowPagingWithoutRepeatingADaySplitAcrossPages() = runBlocking {
        val all = (0 until 40).map { summary("$it", at(8, 23) - it * 3_600_000L, Origin.Menu, whatsapp) }
        val pager = HistoryPager(scope, MutableStateFlow(0L), { cursor, limit ->
            val start = cursor?.let { c -> all.indexOfFirst { it.id == c.id } + 1 } ?: 0
            val page = all.drop(start).take(limit)
            HistoryPage(page, if (start + limit < all.size) page.last().let { HistoryCursor(it.startedAt, it.id) } else null)
        })
        withTimeout(5_000) {
            pager.start()
            val first = pager.state.first { !it.loading }
            assertEquals(HistoryPager.PAGE_SIZE, first.rows.size)
            val firstItems = historyItems(first.rows, zone)
            // 25 hours back from 23:00 on the 8th: the 8th (24 rows) and the first of the 7th.
            assertEquals(listOf("day:2026-11-08", "day:2026-11-07"), firstItems.filterIsInstance<HistoryItem.Day>().map { it.key })
            pager.loadMore()
            val second = pager.state.first { !it.loading && it.rows.size == all.size }
            val items = historyItems(second.rows, zone)
            val days = items.filterIsInstance<HistoryItem.Day>().map { it.date }
            assertEquals(listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 7)), days)
            assertEquals(items.size, items.map { it.key }.distinct().size)
            assertEquals(all.size, items.count { it is HistoryItem.Message })
        }
    }

    @Test fun dayHeadersSayTodayYesterdayThenTheDate() {
        val now = at(8, 20)
        assertEquals("Today", dayLabel(resources, LocalDate.of(2026, 11, 8), now, zone))
        assertEquals("Yesterday", dayLabel(resources, LocalDate.of(2026, 11, 7), now, zone))
        assertEquals("5 Nov", dayLabel(resources, LocalDate.of(2026, 11, 5), now, zone))
        assertEquals("5 Nov 2025", dayLabel(resources, LocalDate.of(2025, 11, 5), now, zone))
    }

    @Test fun theFilterOffersAllEachAppThenChat() {
        assertEquals(
            listOf(HistoryFilter.All, HistoryFilter.App(whatsapp), HistoryFilter.App(messages), HistoryFilter.Chat),
            filterChoices(rows),
        )
        assertEquals(listOf(HistoryFilter.All), filterChoices(rows.filter { it.summary.hostApp == null && it.summary.origin != Origin.Chat }))
        // A chosen source stays offered once its rows are gone, so its empty state can show.
        assertEquals(listOf(HistoryFilter.All, HistoryFilter.Chat), filterChoices(emptyList(), HistoryFilter.Chat))
        assertEquals("WhatsApp", appName(rows, HistoryFilter.App(whatsapp)))
        assertEquals("org.example", appName(rows, HistoryFilter.App("org.example")))
    }

    @Test fun theFilterKeepsOneSourceAndIsSavedAsAToken() {
        assertEquals(listOf("wa1", "wa2"), rows.filter(HistoryFilter.App(whatsapp)::keeps).map { it.summary.id })
        assertEquals(listOf("chat1", "chat2"), rows.filter(HistoryFilter.Chat::keeps).map { it.summary.id })
        assertEquals(rows, rows.filter(HistoryFilter.All::keeps))
        listOf(HistoryFilter.All, HistoryFilter.Chat, HistoryFilter.App(whatsapp)).forEach {
            assertEquals(it, HistoryFilter.read(it.token))
        }
        assertEquals(HistoryFilter.All, HistoryFilter.read(null))
        assertEquals(HistoryFilter.All, HistoryFilter.read("app:"))
        assertEquals(HistoryFilter.All, HistoryFilter.read("something new"))
        // Day headers are built from what the filter keeps.
        assertEquals(listOf("day:2026-11-08", "chat1", "chat2"), historyItems(rows.filter(HistoryFilter.Chat::keeps), zone).map { it.key })
    }

    @Test fun chatRowsAreLabelledWithTheirConversationAndAppRowsWithTheApp() {
        assertEquals("Chat · Weekend plans", rows[0].sourceLabel(resources))
        assertEquals("WhatsApp", rows[1].sourceLabel(resources))
        assertEquals(null, rows.last().sourceLabel(resources))
        val persona = ChatThreadInfo("b", "", "Jordi, my landlord", "ca", 1, 0)
        assertEquals("Chat · Jordi, my landlord", row("c", 1, Origin.Chat, chat = persona).sourceLabel(resources))
        // The conversation lookup failed or it's gone: still a chat message.
        assertEquals("Chat", row("c", 1, Origin.Chat).sourceLabel(resources))
        assertTrue(rows.none { it.source == null && it.summary.origin == Origin.Chat })
        assertFalse(rows.last().source != null)
    }
}
