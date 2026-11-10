package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Lab, Chat tab IA: a conversation row says whose turn it is ("You: …" for the writer's own last
 * message), a screen reader hears everything the row shows, and the list says first when there
 * is no key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConversationListTest {
    private val rent = Conversation("c1", "Rent", "ca", 1, 1, persona = "Jordi, my landlord")
    private val weekend = Conversation("c2", "Weekend", "ca", 1, 1)

    @Test fun theStoreSaysWhoSpokeLast() = runBlocking {
        val store = ChatStore(ApplicationProvider.getApplicationContext<Context>(), name = null)
        try {
            store.create(rent)
            store.add(ChatMessage.partner("p1", "c1", "Hola", 1))
            store.add(ChatMessage("w1", "c1", true, "Bé", "Bé", check = SentCheck.LooksGood, at = 2))
            store.create(weekend)
            store.add(ChatMessage("w2", "c2", true, "Hola", "Hola", check = SentCheck.LooksGood, at = 3))
            store.add(ChatMessage.partner("p2", "c2", "Què fem?", 4))
            val byId = store.conversations().associateBy { it.conversation.id }
            assertTrue(byId.getValue("c1").lastFromWriter)
            assertEquals("Bé", byId.getValue("c1").lastText)
            assertFalse(byId.getValue("c2").lastFromWriter)
        } finally {
            store.close()
        }
    }

    @Test fun theWritersLastMessageReadsYou() {
        show(needsKey = false) { root, activity ->
            assertTrue(hasText(root, activity.getString(R.string.lab_ia_chat_last_you, "Ja ho tinc")))
            assertTrue(hasText(root, "Què fem?"))
            assertFalse(hasText(root, activity.getString(R.string.lab_ia_chat_last_you, "Què fem?")))
        }
    }

    @Test fun aScreenReaderHearsWhoAndTheLastMessage() {
        show(needsKey = false) { root, activity ->
            val with = activity.getString(R.string.lab_chat_talking_to, "Jordi, my landlord")
            val you = activity.getString(R.string.lab_ia_chat_last_you, "Ja ho tinc")
            assertTrue(descriptions(root).any { it.startsWith("Rent, $with, ") && it.endsWith(", $you") })
        }
    }

    @Test fun noKeyIsSaidFirst() {
        show(needsKey = true) { root, activity ->
            assertTrue(hasText(root, activity.getString(R.string.lab_chat_no_key_title)))
        }
        show(needsKey = false) { root, activity ->
            assertFalse(hasText(root, activity.getString(R.string.lab_chat_no_key_title)))
        }
    }

    private fun show(needsKey: Boolean, check: (View, ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val conversations = listOf(
            ConversationSummary(rent, "Ja ho tinc", 2, lastFromWriter = true),
            ConversationSummary(weekend, "Què fem?", 1, lastFromWriter = false),
        )
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                LinguizeTheme(dark = false) {
                    ConversationList(
                        conversations = conversations, language = "ca", people = emptyList(), onLoadPeople = {},
                        onNew = { _, _ -> }, onOpen = {}, onRename = { _, _ -> }, onDelete = {},
                        needsKey = needsKey,
                    )
                }
            }
        }
        var frameTime = 0L
        fun flush() {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            var delivered = 0
            while (recomposer.hasPendingWork || frames.hasAwaiters) {
                check(delivered++ < 120) { "List composition did not become idle" }
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        try {
            activity.setContentView(root)
            flush()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
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

    private fun hasText(root: View, text: String): Boolean = nodes(root).any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun descriptions(root: View): List<String> =
        nodes(root).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

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
