package com.evanaronson.linguize.ui.time

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.time.MessageTime
import com.evanaronson.linguize.ui.chat.ChatCorrections
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Lab, Time spent: one message's time, as a quiet line where the message is looked at. Measured
 * writing and review for a chat message, the writing labelled estimated for another app, and
 * nothing when no time was recorded. Each case is a fresh composition; the time is read in an
 * effect, so the frame clock is driven until it settles before anything is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageTimeLineTest {
    private val times = MessageTimes { id ->
        when (id) {
            "chat" -> MessageTime(80_000, writingEstimated = false, reviewMs = 35_000)
            "menu" -> MessageTime(40_000, writingEstimated = true, reviewMs = 35_000)
            else -> null
        }
    }

    private fun corrected(sessionId: String?) = ChatMessage(
        "w1", "c1", fromWriter = true, raw = "Com estas?", text = "Com estàs?",
        changes = listOf(ChatChange(EditKind.Fix, 4, 9, "estas", "estàs", "Accent marks the verb.")),
        check = SentCheck.Corrected, at = 1, sessionId = sessionId,
    )

    @Test fun aChatMessagesCorrectionsEndWithItsMeasuredTime() = show({ Column { ChatCorrections(corrected("chat"), "ca") } }) { root, activity ->
        assertEquals("Writing 1 min 20 s, reviewing 35 s", activity.getString(R.string.lab_time_spent_message, "1 min 20 s", "35 s"))
        assertTrue(texts(root).toString(), texts(root).contains("Writing 1 min 20 s, reviewing 35 s"))
    }

    @Test fun anotherAppsCheckSaysItsWritingIsEstimated() = show({ Text(messageTimeLine("menu") ?: "none") }) { root, _ ->
        assertTrue(texts(root).toString(), texts(root).contains("Writing about 40 s (estimated), reviewing 35 s"))
    }

    @Test fun nothingShowsWhenNoTimeWasRecorded() {
        show({ Column { ChatCorrections(corrected("older"), "ca") } }) { root, _ ->
            assertFalse(texts(root).toString(), texts(root).any { it.startsWith("Writing") })
            // The corrections themselves are unchanged.
            assertTrue(texts(root).contains("Com estas estàs?"))
        }
        // History off: no session to look up.
        show({ Text(messageTimeLine(null) ?: "none") }) { root, _ -> assertTrue(texts(root).contains("none")) }
    }

    @Test fun oneMessagesDurationKeepsItsSeconds() {
        assertEquals(DurationParts(0, 1, 20), DurationParts.precise(80_000))
        assertEquals(DurationParts(0, 0, 35), DurationParts.precise(35_000))
        assertEquals(DurationParts(0, 2, 0), DurationParts.precise(119_600))
        assertEquals(DurationParts(1, 1, 0), DurationParts.precise(3_660_000))
        // Totals stay rounded to the minute.
        assertEquals(DurationParts(0, 1, 0), DurationParts.of(80_000))
    }

    private fun show(content: @Composable () -> Unit, check: (View, ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { LinguizeTheme(dark = false) { CompositionLocalProvider(LocalMessageTimes provides times) { content() } } }
        }
        var frameTime = 0L
        fun flush() {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            var delivered = 0
            while (recomposer.hasPendingWork || frames.hasAwaiters) {
                check(delivered++ < 240) { "Composition did not become idle" }
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        try {
            activity.setContentView(root)
            flush()
            flush()
            check(root, activity)
        } finally {
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun texts(root: View): List<String> = nodes(root).flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun nodes(root: View): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).unmergedRootSemanticsNode)
    }
}
