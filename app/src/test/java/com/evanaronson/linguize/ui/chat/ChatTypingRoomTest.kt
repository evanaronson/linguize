package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.card.CardTyping
import com.evanaronson.linguize.ui.card.LocalCardTyping
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lab, Type to accept, in Chat: while a change is typed on the review's card, the review takes
 * the height above the keyboard and the conversation yields its room; it stays in place (not
 * removed), and gets its room back as typing ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTypingRoomTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L
    private lateinit var root: ComposeView
    private val typing = CardTyping()
    private var provided: CardTyping? = null

    @After fun tearDown() {
        root.disposeComposition()
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun theReviewTakesTheRoomWhileTyping() {
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                MaterialTheme {
                    ConversationAndReview(
                        typing,
                        conversation = {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(HISTORY)) {
                                item { Text(PARTNER) }
                            }
                        },
                        bottom = { reviewMax ->
                            provided = LocalCardTyping.current
                            // A long card: it fills whatever height the review is given.
                            Box(Modifier.fillMaxWidth().heightIn(max = reviewMax).testTag(REVIEW)) {
                                Box(Modifier.fillMaxWidth().height(5000.dp))
                            }
                        },
                    )
                }
            }
        }
        activity.setContentView(root)
        frames(30)
        assertSame("The card shares the host's typing state", typing, provided)

        val whole = bounds(REVIEW).height + bounds(HISTORY).height
        assertEquals("At most its share while not typing", whole * REVIEW_SHARE, bounds(REVIEW).height, 2f)
        assertTrue("The partner's message shows", hasText(PARTNER))

        setActive(true)
        frames(30)
        assertEquals("The whole height above the keyboard", whole, bounds(REVIEW).height, 1f)
        assertEquals("The conversation yields its room", 0f, bounds(HISTORY).height, 1f)
        assertTrue("…but stays where it was", node(HISTORY) != null)

        setActive(false)
        frames(30)
        assertEquals("Back to its share", whole * REVIEW_SHARE, bounds(REVIEW).height, 2f)
        assertTrue("The conversation is back", hasText(PARTNER))
    }

    /** What the card reports while a field has focus and the keyboard is up (TypeToAcceptLayoutTest covers that). */
    private fun setActive(value: Boolean) {
        typing.active = value
    }

    private fun frames(count: Int) = repeat(count) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frameTime += 16_000_000L
        frames.sendFrame(frameTime)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        // Compose measures and places in draw, which a test doesn't do: ask the owner directly.
        fun measureAndLayout(view: View) {
            view.javaClass.methods.firstOrNull { it.name == "measureAndLayout" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                ?.invoke(view, true)
            if (view is ViewGroup) for (index in 0 until view.childCount) measureAndLayout(view.getChildAt(index))
        }
        measureAndLayout(root)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun node(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun bounds(tag: String) = requireNotNull(node(tag)) { "No $tag" }.let {
        androidx.compose.ui.geometry.Rect(it.positionInWindow, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun nodes(): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }

    private companion object {
        const val HISTORY = "history"
        const val REVIEW = "review"
        const val PARTNER = "Hola! Com estàs?"
    }
}
