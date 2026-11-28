package com.evanaronson.linguize.ui.time

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.time.MessageTime
import com.evanaronson.linguize.core.time.TimeTotals
import com.evanaronson.linguize.testing.OwnComposition
import com.evanaronson.linguize.ui.chat.ChatCorrections
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Lab, Time spent: a check saved before Time spent shows its writing estimated and says its review
 * wasn't timed; the totals say how many messages review doesn't cover. On [OwnComposition]'s frame clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OlderChecksLineTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = controller.get()
    private val compose = OwnComposition(activity)

    private val times = MessageTimes { id ->
        when (id) {
            "old" -> MessageTime(40_000, writingEstimated = true, reviewMs = null)
            "menu" -> MessageTime(40_000, writingEstimated = true, reviewMs = 35_000)
            else -> null
        }
    }

    @After fun tearDown() {
        compose.dispose()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun show(content: @Composable () -> Unit) = compose.show(40) {
        LinguizeTheme(dark = false) { CompositionLocalProvider(LocalMessageTimes provides times) { content() } }
    }

    private fun texts() = compose.nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test fun anOlderCheckSaysItsWritingIsEstimatedAndItsReviewWasntTimed() {
        show { Text(messageTimeLine("old") ?: "none") }
        assertTrue(texts().toString(), texts().contains("Writing about 40 s (estimated), review not timed"))
        show { Text(messageTimeLine("menu") ?: "none") }
        assertTrue(texts().toString(), texts().contains("Writing about 40 s (estimated), reviewing 35 s"))
    }

    @Test fun anOlderChatMessagesCorrectionsEndWithTheLine() {
        val message = ChatMessage(
            "w1", "c1", fromWriter = true, raw = "Com estas?", text = "Com estàs?",
            changes = listOf(ChatChange(EditKind.Fix, 4, 9, "estas", "estàs", "Accent marks the verb.")),
            check = SentCheck.Corrected, at = 1, sessionId = "old",
        )
        show { Column { ChatCorrections(message, "ca") } }
        assertTrue(texts().toString(), texts().contains("Writing about 40 s (estimated), review not timed"))
    }

    @Test fun totalsSayHowManyMessagesReviewDoesntCover() {
        val totals = TimeTotals(
            messages = 8, measuredWritingMs = 60_000, estimatedWritingMs = 120_000, reviewMs = 30_000,
            estimatedMessages = 3, reviewUntimedMessages = 3,
        )
        show { Totals(totals) }
        assertTrue(texts().toString(), texts().contains("8 messages"))
        assertTrue(texts().toString(), texts().contains("Writing: 3 min"))
        assertTrue(texts().toString(), texts().contains("1 min measured, 2 min estimated"))
        assertTrue(texts().toString(), texts().contains("Reviewing: 30 s"))
        assertTrue(texts().toString(), texts().contains("Not timed for 3 older messages"))
        assertEquals("Not timed for 1 older message", activity.resources.getQuantityString(R.plurals.lab_time_spent_review_untimed, 1, 1))

        show { Totals(totals.copy(estimatedWritingMs = 0, estimatedMessages = 0, reviewUntimedMessages = 0)) }
        assertFalse(texts().toString(), texts().any { it.startsWith("Not timed") })
    }
}
