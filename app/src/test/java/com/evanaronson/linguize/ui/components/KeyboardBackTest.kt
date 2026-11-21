package com.evanaronson.linguize.ui.components

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.evanaronson.linguize.accessibility.OverlayWindow
import com.evanaronson.linguize.ui.card.FloatingPopup
import com.evanaronson.linguize.ui.words.ImportDialog
import com.evanaronson.linguize.ui.words.PendingImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * Lab, Consistency: Back while the keyboard is up only closes the keyboard, everywhere: the
 * floating card (in the popup activities and the accessibility window), and dialogs with a
 * field. A second Back, with the keyboard closed, does what it always did. The keyboard is real
 * IME insets sent to the views, or a focused field where a window isn't told about the keyboard.
 * Each page drives its own frame clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardBackTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L
    private var root: ComposeView? = null
    private var closed = 0

    @After fun tearDown() {
        root?.disposeComposition()
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    /** [content] in the activity, under a host that counts Backs that reach it (closing the card). */
    private fun show(content: @Composable () -> Unit) {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closed++ }
        })
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { content() } }
        }
        activity.setContentView(root)
        frames(10)
    }

    private fun frames(count: Int) = repeat(count) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frameTime += 16_000_000L
        frames.sendFrame(frameTime)
        root?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, 1080, 1920)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun keyboard(view: View, up: Boolean) {
        val type = WindowInsetsCompat.Type.ime()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(type, if (up) Insets.of(0, 0, 0, 700) else Insets.NONE)
            .setVisible(type, up)
            .build()
        fun send(view: View) {
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            if (view is ViewGroup) for (index in 0 until view.childCount) send(view.getChildAt(index))
        }
        send(view)
        frames(10)
    }

    private fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        frames(5)
    }

    @Test fun theFloatingCardKeepsItsPlaceWhileBackClosesTheKeyboard() {
        show { FloatingPopup(onDismiss = {}, onOpenApp = {}) { modifier -> Field(modifier) } }
        keyboard(root!!, up = true)
        back()
        assertEquals("Back with the keyboard up leaves the card open", 0, closed)
        keyboard(root!!, up = false)
        back()
        assertEquals("The next Back closes it, as before", 1, closed)
    }

    @Test fun aFocusedCardFieldCountsAsTheKeyboardWhereTheWindowIsNotTold() {
        show { FloatingPopup(onDismiss = {}, onOpenApp = {}) { modifier -> Field(modifier) } }
        focus(nodes(root!!))
        assertEquals(true, field(nodes(root!!))?.config?.getOrNull(SemanticsProperties.Focused))
        assertTrue(FocusedFields.anyIn(innerView(root!!)))
        back()
        assertEquals("The card stays", 0, closed)
        assertNotEquals("Back left the field", true, field(nodes(root!!))?.config?.getOrNull(SemanticsProperties.Focused))
        assertFalse(FocusedFields.anyIn(innerView(root!!)))
        back()
        assertEquals(1, closed)
    }

    @Test fun aDialogWithAFieldClosesTheKeyboardBeforeItself() {
        var cancelled = 0
        show {
            ImportDialog(PendingImport(text = "una\ndos", suggestedName = "Deck"), listOf("es"),
                onImport = { _, _ -> }, onCancel = { cancelled++ })
        }
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        val decor = dialog.window!!.decorView
        focus(nodes(decor))
        assertEquals(true, field(nodes(decor))?.config?.getOrNull(SemanticsProperties.Focused))
        dialog.onBackPressedDispatcher.onBackPressed()
        frames(5)
        assertEquals("The dialog stays", 0, cancelled)
        assertNotEquals(true, field(nodes(decor))?.config?.getOrNull(SemanticsProperties.Focused))
        dialog.onBackPressedDispatcher.onBackPressed()
        frames(5)
        assertEquals("The next Back cancels it, as before", 1, cancelled)
    }

    @Test fun theAccessibilityWindowClosesTheKeyboardBeforeTheCard() {
        var backs = 0
        val requester = FocusRequester()
        val window = OverlayWindow(activity) { backs++ }
        try {
            window.show {
                MaterialTheme {
                    var text by remember { mutableStateOf("") }
                    LinguizeTextField(text, { text = it }, fieldModifier = Modifier.focusRequester(requester), accessibleLabel = LABEL)
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            requester.requestFocus()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A card field has focus", window.keyboardUp())
            window.onBackKey()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Back with a card field focused keeps the card", 0, backs)
            assertFalse("…and leaves the field", window.keyboardUp())
            window.onBackKey()
            assertEquals("The next Back closes the card, as before", 1, backs)
        } finally {
            window.remove()
        }
    }

    @Test fun theAccessibilityWindowsBackKeyFollowsTheSameRule() {
        var backs = 0
        val requester = FocusRequester()
        val window = OverlayWindow(activity) { backs++ }
        try {
            window.show {
                MaterialTheme {
                    var text by remember { mutableStateOf("") }
                    LinguizeTextField(text, { text = it }, fieldModifier = Modifier.focusRequester(requester), accessibleLabel = LABEL)
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            requester.requestFocus()
            shadowOf(Looper.getMainLooper()).idle()
            val windowRoot = org.robolectric.util.ReflectionHelpers.getField<View>(window, "root")
            fun press() {
                windowRoot.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                windowRoot.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                shadowOf(Looper.getMainLooper()).idle()
            }
            press()
            assertEquals(0, backs)
            press()
            assertEquals(1, backs)
        } finally {
            window.remove()
        }
    }

    @Composable
    private fun Field(modifier: Modifier) {
        var text by remember { mutableStateOf("") }
        LinguizeTextField(text, { text = it }, modifier = modifier, accessibleLabel = LABEL)
    }

    /** The (first) text field among [nodes]. */
    private fun field(nodes: List<SemanticsNode>) = nodes.firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }

    /** A tap on the field: it asks for focus, as touching it does. */
    private fun focus(nodes: List<SemanticsNode>) {
        val field = requireNotNull(field(nodes)) { "No field" }
        assertTrue(requireNotNull(field.config[SemanticsActions.RequestFocus].action).invoke())
        frames(3)
    }

    /** The view that hosts a composition: what [FocusedFields] knows fields by. */
    private fun innerView(view: View): View {
        if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val found = runCatching { innerView(view.getChildAt(index)) }.getOrNull()
            if (found != null) return found
        }
        throw IllegalStateException("No composition under $view")
    }

    private fun nodes(view: View): List<SemanticsNode> {
        val owner = innerView(view).let { host ->
            host.javaClass.methods.first { it.name == "getSemanticsOwner" && it.parameterCount == 0 }.invoke(host) as SemanticsOwner
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(owner.rootSemanticsNode)
    }

    private companion object {
        const val LABEL = "Test field"
    }
}
