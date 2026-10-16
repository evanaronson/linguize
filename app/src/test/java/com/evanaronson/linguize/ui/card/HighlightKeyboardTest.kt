package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Dispatch real hardware keys into Compose; semantics actions do not stand in for keyboard entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightKeyboardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun keyboardActivatesEachExistingHighlightAndReachesItsAcceptButton() {
        val accepted = mutableListOf<Int>()
        activity.setContent {
            MaterialTheme {
                HighlightedText("First second", listOf(
                    Highlight(10, 0..4, "first", "First explanation"),
                    Highlight(20, 6..11, "Second", "Second explanation"),
                ), Color.Blue, onReplace = { accepted += it }, onCopy = {}, copyDescription = "Copy")
            }
        }
        settle()
        tabToHighlight(0, 5)
        assertTrue("Existing highlight receives keyboard focus",
            nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true })
        assertTrue("Keyboard focus visibly underlines the existing word", textLayout().second.layoutInput.text.spanStyles.any {
            it.start == 0 && it.end == 5 && it.item.textDecoration == TextDecoration.Underline
        })
        key(KeyEvent.KEYCODE_ENTER)
        assertTrue("Enter opens first explanation", hasText("First explanation"))
        tabToHighlight(6, 12)
        key(KeyEvent.KEYCODE_SPACE)
        assertTrue("Space opens second explanation", hasText("Second explanation"))
        // The existing Accept control remains reachable through standard Tab traversal.
        tabToText("Accept")
        assertTrue("Accept is keyboard reachable", nodes().any {
            it.config.getOrNull(SemanticsProperties.Focused) == true &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == "Accept" } == true
        })
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(20), accepted)
    }

    @Test fun acceptanceRecomposesLinksAndKeyboardCanReviewTheRemainingChange() {
        val first = Highlight(10, 0..4, "first", "First explanation")
        val second = Highlight(20, 6..11, "Second", "Second explanation")
        val remaining = mutableStateOf(listOf(first, second))
        val accepted = mutableListOf<Int>()
        activity.setContent {
            MaterialTheme {
                HighlightedText("First second", remaining.value, Color.Blue,
                    onReplace = { id -> accepted += id; remaining.value = remaining.value.filterNot { it.id == id } },
                    onCopy = {}, copyDescription = "Copy")
            }
        }
        settle()
        tabToHighlight(0, 5)
        key(KeyEvent.KEYCODE_ENTER)
        tabToText("Accept")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(10), accepted)
        assertEquals(listOf(second), remaining.value)
        // The accepted link and its button leave composition. Traversal must still reach
        // and activate the remaining inline link, without a pointer or semantics click.
        repeat(4) {
            key(KeyEvent.KEYCODE_TAB)
            key(KeyEvent.KEYCODE_ENTER)
            if (hasText("Second explanation")) return
        }
        fail("Keyboard focus did not recover after accepting a change")
    }

    @Test fun readOnlyLinksKeepWhyAndCopyButCannotAcceptEvenAfterCapabilityChanges() {
        val editable = mutableStateOf(true)
        val accepted = mutableListOf<Int>()
        var copies = 0
        activity.setContent {
            MaterialTheme {
                HighlightedText("First second", listOf(
                    Highlight(10, 0..4, "first", "First explanation"),
                    Highlight(20, 6..11, "Second", "Second explanation"),
                ), Color.Blue, onReplace = if (editable.value) ({ id -> accepted += id }) else null,
                    onCopy = { copies++ }, copyDescription = "Copy")
            }
        }
        settle()
        assertEquals(5, customActions().size) // two Why, two Accept, Copy
        editable.value = false
        settle()
        assertEquals("Revoked acceptance: ${customActions().map { it.label }}", 3, customActions().size) // two Why, Copy
        tabToHighlight(0, 5)
        key(KeyEvent.KEYCODE_ENTER)
        assertTrue(hasText("First explanation"))
        assertFalse(hasText("Accept"))
        tabToDescription("Copy")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(1, copies)
        assertTrue(accepted.isEmpty())
    }

    private fun customActions() = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }

    private fun tabToHighlight(start: Int, end: Int) {
        repeat(8) {
            key(KeyEvent.KEYCODE_TAB)
            if (textLayout().second.layoutInput.text.spanStyles.any {
                it.start == start && it.end == end && it.item.textDecoration == TextDecoration.Underline
            }) return
        }
        fail("Tab did not reach highlighted range $start..$end")
    }

    private fun tabToText(text: String) {
        repeat(8) {
            key(KeyEvent.KEYCODE_TAB)
            if (nodes().any { node -> node.config.getOrNull(SemanticsProperties.Focused) == true &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }) return
        }
        fail("Tab did not reach $text")
    }

    private fun tabToDescription(description: String) {
        repeat(8) {
            key(KeyEvent.KEYCODE_TAB)
            if (nodes().any { node -> node.config.getOrNull(SemanticsProperties.Focused) == true &&
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }) return
        }
        fail("Tab did not reach $description")
    }

    @Test fun directTapsToggleOnceAndNearMissRetainsThePunctuationAllowance() {
        activity.setContent {
            MaterialTheme {
                HighlightedText("Hello,                                     world", listOf(
                    Highlight(10, 5..5, ";", "Comma explanation"),
                ), Color.Blue, onReplace = null, onCopy = {}, copyDescription = "Copy")
            }
        }
        settle()
        val (node, layout) = textLayout()
        val glyph = layout.getBoundingBox(5)
        tap(node, glyph.center)
        assertTrue("Direct link tap opens the existing panel once", hasText("Comma explanation"))
        tap(node, glyph.center)
        assertFalse("Second tap closes the panel once", hasText("Comma explanation"))
        val density = activity.resources.displayMetrics.density
        tap(node, Offset(glyph.right + 23 * density, glyph.center.y))
        assertTrue("Near miss within 24dp still opens punctuation", hasText("Comma explanation"))
        tap(node, Offset(glyph.right + 25 * density, glyph.center.y))
        assertFalse("Tap beyond 24dp clears selection", hasText("Comma explanation"))
    }

    @Test fun wrappedAdjacentRtlLinksRemainDistinctWithASpacingPanel() {
        val text = "אבגדהוזחטיכלמנסעפצקרשת "
        val first = Highlight(10, 0..7, "אבגד", "First explanation")
        val second = Highlight(20, 8 until text.lastIndex, "הוזח", "Second explanation")
        val spacing = Highlight(30, text.lastIndex..text.lastIndex, "\n", "Spacing explanation")
        activity.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(120.dp)) {
                        HighlightedText(text, listOf(first, second, spacing), Color.Blue,
                            onReplace = null, onCopy = {}, copyDescription = "Copy")
                    }
                }
            }
        }
        settle()
        assertEquals(4, customActions().size) // three Why, Copy; no acceptance in read-only mode
        val (node, layout) = textLayout()
        assertTrue("Exercise wrapped shaping", layout.lineCount > 1)
        for (change in listOf(first, second)) {
            tap(node, layout.getBoundingBox(change.range.last).center)
            assertTrue("Wrapped RTL glyph opens its own explanation", hasText(change.why!!))
            tap(node, layout.getBoundingBox(change.range.last).center)
        }
        for (change in listOf(first, second)) {
            tabToHighlight(change.range.first, change.range.last + 1)
            key(KeyEvent.KEYCODE_ENTER)
            assertTrue("Keyboard opens the selected wrapped RTL link", hasText(change.why!!))
        }
        // Existing custom Why for invisible edits still expands its original panel.
        assertTrue(customActions().single { it.label.contains("spacing", ignoreCase = true) ||
            it.label.contains("line break", ignoreCase = true) }.action())
        settle()
        assertTrue(hasText("Spacing explanation"))
        assertFalse(hasText("Accept"))
    }

    private fun tap(node: SemanticsNode, position: Offset) {
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val composeLocation = IntArray(2).also { ownerView(root)!!.getLocationOnScreen(it) }
        val point = node.positionInRoot + position + Offset(
            (composeLocation[0] - rootLocation[0]).toFloat(),
            (composeLocation[1] - rootLocation[1]).toFloat(),
        )
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 10 else 0,
                action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        settle()
    }

    private fun textLayout(): Pair<SemanticsNode, TextLayoutResult> {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.CustomActions) != null }
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return node to results.single()
    }

    private fun key(code: Int) {
        // A physical window assigns Android focus before delivering hardware keys.
        // Direct Robolectric dispatch bypasses that ViewRootImpl step.
        val view = requireNotNull(ownerView(root))
        if (view.isInTouchMode || root.findFocus() == null) {
            assertTrue("Android Compose host enters keyboard mode", view.requestFocusFromTouch())
        }
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun ownerView(view: View): View? {
        if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) ownerView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun nodes(): List<SemanticsNode> {
        val view = requireNotNull(ownerView(root))
        val owner = view.javaClass.methods.first { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            .invoke(view) as SemanticsOwner
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(owner.rootSemanticsNode)
    }
}
