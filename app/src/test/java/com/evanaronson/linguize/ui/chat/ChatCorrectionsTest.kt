package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Lab, Chat corrections: a corrected message's bubble says how it was checked at its bottom end,
 * a tap on it opens History's own rows for its changes under it (and closes them), a reply's
 * tap still flips it, and the review's Edit message and system Back both go back to the draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatCorrectionsTest {
    private val corrected = ChatMessage(
        "w1", "c1", fromWriter = true, raw = "Com estas?", text = "Com estàs?",
        changes = listOf(ChatChange(EditKind.Fix, 4, 9, "estas", "estàs", "Accent marks the verb.")),
        check = SentCheck.Corrected, at = 1, sessionId = "s1",
    )
    private val clean = ChatMessage("w2", "c1", true, "Bé", "Bé", check = SentCheck.LooksGood, at = 2)
    private val reply = ChatMessage.partner("p1", "c1", "Què fem?", 3, translation = "What shall we do?")

    @Test fun theAdapterFeedsHistorysRowsWithTheAcceptedChanges() {
        val rows = chatSuggestionRows(corrected)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("w1:0", row.id)
        assertEquals(EditKind.Fix, row.kind)
        assertEquals(4 to 9, row.start to row.end)
        assertEquals("estas" to "estàs", row.fromText to row.toText)
        assertEquals("Accent marks the verb.", row.why)
        assertEquals(Decision.Accepted, row.decision)
        assertTrue(hasCorrections(corrected))
        assertFalse(hasCorrections(clean))
        assertFalse(hasCorrections(reply))
    }

    @Test fun tappingACorrectedBubbleShowsHistorysCorrectionsAndHidesThem() {
        var expanded by mutableStateOf(false)
        show({
            MessageBubble(corrected, expanded, onToggle = { expanded = !expanded }, flipped = null, onFlip = {}, hint = false,
                language = "ca")
        }) { root, activity, flush ->
            val label = activity.resources.getQuantityString(R.plurals.lab_chat_changes_count, 1, 1)
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label)
            assertEquals(activity.getString(R.string.lab_expand_pattern_collapsed),
                bubble.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(activity.getString(R.string.lab_chat_corrections_show), bubble.config[SemanticsActions.OnClick].label)
            assertTrue(bubble.config[SemanticsActions.CustomActions].any {
                it.label == activity.getString(R.string.lab_chat_corrections_show)
            })
            assertFalse(hasText(root, activity.getString(R.string.history_before)))

            bubble.config[SemanticsActions.OnClick].action!!.invoke()
            flush()
            // History's own Before and Suggestions, as a saved check shows them.
            assertTrue(hasText(root, activity.getString(R.string.history_before)))
            assertTrue(hasText(root, "Com estas?"))
            assertTrue(hasText(root, activity.getString(R.string.history_suggestions)))
            assertTrue(descriptions(root).contains(
                activity.getString(R.string.change_becomes, "estas", "estàs")))
            assertTrue(hasText(root, activity.getString(R.string.history_applied)))
            assertTrue(nodes(root).any { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.history_show_explanation)
            })
            val open = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label)
            assertEquals(activity.getString(R.string.lab_expand_pattern_expanded),
                open.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(activity.getString(R.string.lab_chat_corrections_hide), open.config[SemanticsActions.OnClick].label)

            open.config[SemanticsActions.OnClick].action!!.invoke()
            flush()
            assertFalse(hasText(root, activity.getString(R.string.history_before)))
        }
    }

    @Test fun theCheckLineSitsAtTheBubblesBottomEnd() {
        show({
            MessageBubble(corrected, false, onToggle = {}, flipped = null, onFlip = {}, hint = false)
        }) { root, activity, _ ->
            val label = activity.resources.getQuantityString(R.plurals.lab_chat_changes_count, 1, 1)
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Com estàs?") + ". " + label).boundsInRoot
            val meta = nodes(root).single { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG }.boundsInRoot
            val padding = 14 * activity.resources.displayMetrics.density
            assertTrue("inside the bubble", bubble.contains(meta.center))
            assertEquals("at its end", bubble.right - padding, meta.right, 2f)
            assertTrue("at its bottom", meta.top > bubble.top + bubble.height / 2)
        }
    }

    @Test fun aCleanMessageSaysLooksGoodInTheSamePlaceAndOpensNothing() {
        show({
            MessageBubble(clean, false, onToggle = { error("nothing to open") }, flipped = null, onFlip = {}, hint = false)
        }) { root, activity, _ ->
            val bubble = bubble(root, activity.getString(R.string.lab_chat_you_said, "Bé") + ". " +
                activity.getString(R.string.lab_chat_looks_good))
            assertNull(bubble.config.getOrNull(SemanticsActions.OnClick))
            assertNull(bubble.config.getOrNull(SemanticsProperties.StateDescription))
            assertNotNull(nodes(root).singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG })
            // Copy stays.
            assertTrue(bubble.config[SemanticsActions.CustomActions].any { it.label == activity.getString(R.string.lab_chat_copy) })
        }
    }

    @Test fun aReplysTapStillTranslatesIt() {
        var flips = 0
        show({
            MessageBubble(reply, false, onToggle = { error("replies have no corrections") }, flipped = null,
                onFlip = { flips++ }, hint = false)
        }) { root, activity, _ ->
            val bubble = bubble(root, activity.getString(R.string.lab_chat_partner_said, "Què fem?"))
            assertEquals(activity.getString(R.string.lab_chat_show_translation), bubble.config[SemanticsActions.OnClick].label)
            bubble.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, flips)
            assertTrue(nodes(root).none { it.config.getOrNull(SemanticsProperties.TestTag) == CHECK_META_TAG })
        }
    }

    @Test fun editMessageAndSystemBackBothGoBackToTheDraft() {
        var backs = 0
        show({ BackToDraft({ backs++ }) }) { root, activity, flush ->
            assertTrue(hasText(root, activity.getString(R.string.lab_chat_corrections_edit_message)))
            val button = nodes(root).single { it.config.getOrNull(SemanticsActions.OnClick) != null }
            button.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, backs)
            activity.onBackPressedDispatcher.onBackPressed()
            flush()
            assertEquals(2, backs)
        }
    }

    private fun bubble(root: View, description: String): SemanticsNode = nodes(root).single { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }

    private fun show(content: @Composable () -> Unit, check: (View, ComponentActivity, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { LinguizeTheme(dark = false) { content() } }
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
            check(root, activity, ::flush)
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
