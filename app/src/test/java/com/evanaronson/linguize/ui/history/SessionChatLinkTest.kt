package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every History row opens the same saved-check page. A chat message's page also offers
 * "Open conversation"; other messages' pages don't, and their conversation is never looked up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionChatLinkTest {
    private val weekend = ChatThreadInfo("c1", "Weekend plans", null, "es", messages = 3, corrected = 1)

    @Test fun aChatMessageOffersItsConversation() {
        val opened = mutableListOf<String>()
        show(Origin.Chat, opened) { root, activity, lookups ->
            assertEquals(1, lookups.get())
            assertTrue(hasText(root, "Chat · Weekend plans"))
            clickText(root, activity.getString(R.string.lab_chat_open_conversation))
            assertEquals(listOf("c1"), opened)
        }
    }

    @Test fun otherMessagesDoNot() {
        show(Origin.Menu, mutableListOf()) { root, activity, lookups ->
            assertEquals(0, lookups.get())
            assertFalse(hasText(root, activity.getString(R.string.lab_chat_open_conversation)))
            assertTrue(hasText(root, "Saved text"))
        }
    }

    @Test fun aChatMessageWhoseConversationIsGoneIsStillAChatMessage() {
        show(Origin.Chat, mutableListOf(), conversation = null) { root, activity, _ ->
            assertNull((pageOf(root) as? SessionPage.Shown)?.chat)
            assertTrue(hasText(root, activity.getString(R.string.lab_chat_origin_chat)))
            assertFalse(hasText(root, activity.getString(R.string.lab_chat_open_conversation)))
        }
    }

    private var currentModel: SessionViewModel? = null
    private fun pageOf(@Suppress("UNUSED_PARAMETER") root: View) = currentModel?.page

    private fun show(
        origin: Origin,
        opened: MutableList<String>,
        conversation: ChatThreadInfo? = weekend,
        check: (View, ComponentActivity, AtomicInteger) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val detail = SessionRecording("Saved text", SessionContext(
            Opening(origin, null, "es", SessionSettings("English", "moderate", "both", "gemini", "m", "p")),
            "1", "device"), 1, newId = { "check" }).close(2, "Saved text")
        val history = object : HistoryStore by SqliteHistoryStore(activity, "unused") {
            override suspend fun detail(id: String): SessionDetailResult = SessionDetailResult.Found(detail)
        }
        val lookups = AtomicInteger()
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(activity.packageManager), scope, {},
            chatOf = { lookups.incrementAndGet(); conversation })
        currentModel = model
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { SessionScreen(model, onDeleted = {}, onOpenConversation = { opened += it }) } }
        }
        var frameTime = 0L
        fun settle() {
            fun flush() {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                var delivered = 0
                while (recomposer.hasPendingWork || frames.hasAwaiters) {
                    check(delivered++ < 120) { "Detail composition did not become idle" }
                    frameTime += 16_666_667L
                    frames.sendFrame(frameTime)
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            flush()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            flush()
        }
        try {
            model.load(detail.session.id)
            waitFor { model.page is SessionPage.Shown }
            activity.setContentView(root)
            settle()
            check(root, activity, lookups)
        } finally {
            scope.cancel()
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun clickText(root: View, text: String) {
        val node = nodes(root).first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun hasText(root: View, text: String): Boolean = nodes(root).any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun nodes(root: View): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for detail")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
