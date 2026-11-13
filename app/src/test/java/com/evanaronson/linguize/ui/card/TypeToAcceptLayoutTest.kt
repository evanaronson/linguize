package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.awaitCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.teachme.LocalLessonTeacher
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
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
import org.robolectric.annotation.GraphicsMode

/**
 * Lab, Type to accept, on the shared card: the room to type (the text around the change and the
 * field in view together while the keyboard is up), the success beat before each accept, its
 * feedback, and Teach me closing the keyboard. The page drives its own frame clock, so every
 * wait here is frames, never sleeping; the keyboard is real IME insets sent to the view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeToAcceptLayoutTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()

    // A long text: the change sits in the middle, far below the card's first screen.
    private val sentences = (1..60).map { "Frase numero $it amb unes quantes paraules per omplir la linia." }
    private val text = sentences.joinToString(" ")
    private val middle = text.indexOf("numero 30 ") + "numero 30 ".length
    private val fix = Edit(1, EditKind.Fix, middle, middle + 3, "amb", "amb la", "Why amb la",
        assessment = SuggestionAssessment(confidence = 8, impact = 6))
    private val later = text.indexOf("numero 50 ") + "numero 50 ".length
    private val second = Edit(2, EditKind.Fix, later, later + 3, "amb", "dins", "Why dins")
    private val result = CheckResult.Reviewed(Revision(text, listOf(fix, second)), listOf(EditKind.Fix))

    private val state = mutableStateOf<CardState>(CardState.Done(result))
    private val shown = mutableStateOf(true)
    private val accepted = mutableListOf<Int>()
    private val typing = CardTyping()
    private val feedback = object : FeedbackOutput {
        var haptics = 0
        var sounds = 0
        override fun haptic() { haptics++ }
        override fun sound() { sounds++ }
    }
    private var focusManager: FocusManager? = null

    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L
    private lateinit var root: ComposeView

    @After fun tearDown() {
        TypeToAcceptPreferences(activity).apply { enabled = false; vibrate = true; sound = false }
        if (::root.isInitialized) root.disposeComposition()
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private val revision get() = ((state.value as CardState.Done).result as CheckResult.Reviewed).revision

    private fun show(typeToAccept: Boolean, reviewed: CheckResult.Reviewed = result) {
        TypeToAcceptPreferences(activity).enabled = typeToAccept
        state.value = CardState.Done(reviewed)
        val actions = CardActions(
            onCopy = { _, _ -> },
            review = ReviewActions(
                onAccept = { id ->
                    accepted += id
                    val done = state.value as CardState.Done
                    val reviewed = done.result as CheckResult.Reviewed
                    state.value = done.copy(result = reviewed.copy(revision = reviewed.revision.accept(id)))
                },
                onAcceptAll = {}, onUndo = {}, onApply = {},
            ),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
        )
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                focusManager = LocalFocusManager.current
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalCardTyping provides typing,
                        LocalFeedbackOutput provides feedback,
                        LocalLessonTeacher provides { _ -> awaitCancellation() },
                    ) {
                        // In a box, so the card gets loose constraints and its own maximum height.
                        Box { if (shown.value) ResultCard(state.value, actions, Modifier.fillMaxWidth().heightIn(max = CARD_HEIGHT.dp)) }
                    }
                }
            }
        }
        activity.setContentView(root)
        frames(10)
    }

    /** Sends [count] frames, 16 ms apart, laying out after each. */
    private fun frames(count: Int) = repeat(count) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        frameTime += 16_000_000L
        frames.sendFrame(frameTime)
        layout()
    }

    private fun millis(time: Long) = frames((time / 16 + 1).toInt())

    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        fun measureAndLayout(view: View) {
            view.javaClass.methods.firstOrNull { it.name == "measureAndLayout" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                ?.invoke(view, true)
            if (view is ViewGroup) for (index in 0 until view.childCount) measureAndLayout(view.getChildAt(index))
        }
        measureAndLayout(root)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The keyboard, as the system reports it: IME insets sent to the window's views. */
    private fun keyboard(up: Boolean) {
        val type = WindowInsetsCompat.Type.ime()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(type, if (up) Insets.of(0, 0, 0, 700) else Insets.NONE)
            .setVisible(type, up)
            .build()
        fun send(view: View) {
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            if (view is ViewGroup) for (index in 0 until view.childCount) send(view.getChildAt(index))
        }
        send(root)
        millis(TYPING_HANDOFF_MILLIS + 600)
    }

    @Test fun whileTypingTheChangeAndTheFieldAreInViewTogether() {
        show(typeToAccept = true)
        val label = label("amb la")
        assertEquals("The field has focus", true, field(label)?.config?.getOrNull(SemanticsProperties.Focused))
        keyboard(up = true)
        assertTrue("The field has focus and the keyboard is up", typing.active)

        val viewport = viewport()
        val change = changeBounds(middle)
        val field = requireNotNull(field(label)).boundsInWindow
        assertTrue("The change is in view: $change in $viewport", change.top >= viewport.top && change.bottom <= viewport.bottom)
        assertTrue("The field is in view: $field in $viewport", field.top >= viewport.top && field.bottom <= viewport.bottom)
        assertTrue("The change line is shown", hasText(activity.getString(R.string.change_replacement, "amb", "amb la")))

        // Nothing is taken off the card: the rest sits below the field, where scrolling reaches it.
        val meters = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> "Impact" in d } == true }
        assertTrue("Impact and Confidence stay, below the field", unclipped(meters).top >= field.bottom)
        assertTrue("Accept all stays", hasText(activity.getString(R.string.accept_all)))
        // The narrowed text is still the whole text for screen readers.
        assertTrue(textNode().config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Frase numero 1 ") } == true)
    }

    @Test fun inALowerSectionTheCardScrollsToTheChangeAndTheField() {
        // A deletion keeps Accept; Skip moves on to a rewording in the second passage, whose
        // text sits under the whole first passage, far below the card's first screen.
        val deletion = Edit(3, EditKind.Fix, 0, 6, "Frase ", "", "Why not Frase")
        val rewording = Edit(4, EditKind.Natural, middle, middle + 3, "amb", "amb la", "Why amb la")
        show(typeToAccept = true, reviewed = CheckResult.Reviewed(Revision(text, listOf(deletion, rewording)),
            listOf(EditKind.Fix, EditKind.Natural)))
        assertTrue(buttons(R.string.accept).isNotEmpty())
        assertTrue(requireNotNull(buttons(R.string.lab_review_flow_skip).single().config[SemanticsActions.OnClick].action).invoke())
        frames(10)
        val label = label("amb la")
        assertNotNull(field(label))
        keyboard(up = true)
        assertTrue(typing.active)

        val viewport = viewport()
        val text = textNodes().last()
        val results = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val change = results.first().getBoundingBox(middle).translate(text.positionInWindow)
        val field = requireNotNull(field(label)).boundsInWindow
        assertTrue("Scrolled down to it", scrollValue() > 0f)
        assertTrue("The change is in view: $change in $viewport", change.top >= viewport.top && change.bottom <= viewport.bottom)
        assertTrue("The field is in view: $field in $viewport", field.top >= viewport.top && field.bottom <= viewport.bottom)
    }

    @Test fun whenTheKeyboardClosesTheLayoutRelaxes() {
        show(typeToAccept = true)
        keyboard(up = true)
        assertTrue(typing.active)
        assertTrue("Narrowed while typing", textNode().boundsInWindow.height < fullTextHeight())

        keyboard(up = false)
        assertFalse(typing.active)
        millis(400)
        assertEquals("The whole text again", fullTextHeight(), unclipped(textNode()).height, 1f)

        keyboard(up = true)
        assertTrue(typing.active)
        focusManager!!.clearFocus()
        millis(TYPING_HANDOFF_MILLIS + 400)
        assertFalse("Focus leaving the field ends it too", typing.active)
    }

    @Test fun offTheCardIsUnchanged() {
        show(typeToAccept = false)
        keyboard(up = true)
        assertFalse(typing.active)
        assertTrue(fields().isEmpty())
        assertEquals(1, buttons(R.string.accept).size)
        assertEquals("The card shows its own top first", 0f, scrollValue(), 0.5f)
        assertEquals("The text is whole", fullTextHeight(), unclipped(textNode()).height, 1f)
    }

    @Test fun anExactMatchShowsCorrectThenAcceptsOnceAndKeepsFocus() {
        show(typeToAccept = true)
        keyboard(up = true)
        type(label("amb la"), "amb la")
        val correct = activity.getString(R.string.lab_type_to_accept_correct)
        assertNotNull("The field shows it is correct", nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(correct) == true &&
                it.config.getOrNull(SemanticsProperties.LiveRegion) != null
        })
        assertEquals("The change resolves in the text", fix.id, typing.resolving)
        assertTrue("Not accepted yet", accepted.isEmpty())
        assertEquals(1, feedback.haptics)
        assertEquals("Sound is off by default", 0, feedback.sounds)

        type(label("amb la"), "amb lax")
        assertEquals("Locked during the hold", "amb la",
            field(label("amb la"))?.config?.getOrNull(SemanticsProperties.EditableText)?.text)

        millis(CORRECT_HOLD_MILLIS - 200)
        assertTrue("Still holding", accepted.isEmpty())
        millis(300)
        assertEquals("Accepted once, after the hold", listOf(fix.id), accepted)
        assertNull(typing.resolving)

        val next = field(label("dins"))
        assertNotNull("On to the next change", next)
        assertEquals("", next?.config?.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("The field keeps focus, so the keyboard stays", true, next?.config?.getOrNull(SemanticsProperties.Focused))
        millis(1000)
        assertEquals(listOf(fix.id), accepted)
    }

    @Test fun leavingDuringTheHoldStillAcceptsOnce() {
        show(typeToAccept = true)
        type(label("amb la"), "amb la")
        assertTrue(accepted.isEmpty())
        shown.value = false
        frames(3)
        assertEquals(listOf(fix.id), accepted)
        millis(CORRECT_HOLD_MILLIS + 200)
        assertEquals("Once", listOf(fix.id), accepted)
        assertEquals(setOf(fix.id), revision.acceptedEdits.map { it.id }.toSet())
    }

    @Test fun theSwitchesDecideTheFeedback() {
        TypeToAcceptPreferences(activity).apply { vibrate = false; sound = true }
        show(typeToAccept = true)
        type(label("amb la"), "amb la")
        assertEquals(0, feedback.haptics)
        assertEquals(1, feedback.sounds)
    }

    @Test fun teachMeClosesTheKeyboardAndKeepsWhatWasTyped() {
        show(typeToAccept = true)
        keyboard(up = true)
        type(label("amb la"), "amb")
        val teach = nodes().first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == activity.getString(R.string.lab_teach_me_open) } == true ||
                it.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.lab_teach_me_open_action)
        }.let { node -> generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null } }
        assertTrue(requireNotNull(teach.config[SemanticsActions.OnClick].action).invoke())
        millis(TYPING_HANDOFF_MILLIS + 200)
        val field = field(label("amb la"))
        assertEquals("Focus leaves the field", false, field?.config?.getOrNull(SemanticsProperties.Focused))
        assertEquals("What was typed stays", "amb", field?.config?.getOrNull(SemanticsProperties.EditableText)?.text)
        assertFalse(typing.active)
    }

    private fun label(target: String) = activity.getString(R.string.lab_type_to_accept_label, target)

    private fun type(label: String, value: String) {
        val action = requireNotNull(field(label)) { "No field labelled $label" }.config[SemanticsActions.SetText].action
        assertTrue(requireNotNull(action).invoke(AnnotatedString(value)))
        frames(3)
    }

    /** The card's scrolling viewport. */
    private fun viewport(): Rect = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.boundsInWindow

    private fun scrollValue(): Float =
        nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun textNode() = textNodes().first()

    private fun textNodes() = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> "numero 1 " in t.text } == true &&
            it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
    }

    private fun textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        textNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun fullTextHeight() = textLayout().size.height.toFloat()

    /** Where a node's content really is, before any clipping. */
    private fun unclipped(node: SemanticsNode): Rect = Rect(node.positionInWindow, node.size.let {
        androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat())
    })

    /** Where the character at [offset] of the text is drawn, in the window. */
    private fun changeBounds(offset: Int): Rect = textLayout().getBoundingBox(offset).translate(textNode().positionInWindow)

    private fun hasText(value: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true
    }

    private fun fields() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }

    private fun field(label: String) = fields().singleOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun buttons(id: Int) = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(id) } == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
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
        const val CARD_HEIGHT = 200
    }
}
