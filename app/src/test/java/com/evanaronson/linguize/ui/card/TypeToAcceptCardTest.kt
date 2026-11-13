package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
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
import java.time.Duration

private const val SETTLE_FRAMES = 64

/** Lab, Type to accept: on the shared card, the open change's Accept becomes a field to type it in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeToAcceptCardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView

    private val text = "Ayer yo fui al cine con mis amigo"
    private val amigo = text.indexOf("amigo")
    private val deletion = Edit(1, EditKind.Fix, 5, 8, "yo ", "", "Why yo")
    private val fix = Edit(2, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos")
    private val rewording = Edit(3, EditKind.Natural, text.indexOf("cine"), text.indexOf("cine") + 4,
        "cine", "cinema", "Why cinema")
    private val result = CheckResult.Reviewed(Revision(text, listOf(fix, rewording, deletion)),
        listOf(EditKind.Fix, EditKind.Natural))

    private val state = mutableStateOf<CardState>(CardState.Done(result))
    private val accepted = mutableListOf<Int>()

    @After fun tearDown() {
        TypeToAcceptPreferences(activity).enabled = false
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private val revision get() = ((state.value as CardState.Done).result as CheckResult.Reviewed).revision

    private fun update(change: (Revision) -> Revision) {
        val done = state.value as CardState.Done
        val reviewed = done.result as CheckResult.Reviewed
        state.value = done.copy(result = reviewed.copy(revision = change(reviewed.revision)))
    }

    private fun show(typeToAccept: Boolean) {
        TypeToAcceptPreferences(activity).enabled = typeToAccept
        state.value = CardState.Done(result)
        val actions = CardActions(
            onCopy = { _, _ -> },
            review = ReviewActions(
                onAccept = { id -> accepted += id; update { it.accept(id) } },
                onAcceptAll = { kind -> update { it.acceptAll(kind) } },
                onUndo = { update { it.undo() } },
                onApply = {},
            ),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
        )
        // Its own frame clock: the correct-answer hold waits in frames, and the shared clock
        // can stall in a full run (see LanguagesScreenTest).
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContentView(ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalFeedbackOutput provides silent) {
                        ResultCard(state.value, actions, Modifier.fillMaxWidth().heightIn(max = 600.dp))
                    }
                }
            }
        })
        settle()
    }

    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L
    private val silent = object : FeedbackOutput {
        override fun haptic() = Unit
        override fun sound() = Unit
    }

    @Test fun offTheCardIsUnchanged() {
        show(typeToAccept = false)
        assertTrue(hasText("Why yo"))
        assertEquals(1, buttons(R.string.accept).size)
        assertTrue("No field to type in", fields().isEmpty())
        click(R.string.accept)
        assertTrue(hasText("Why amigos"))
        assertEquals("Accept stays a button for every change", 1, buttons(R.string.accept).size)
        assertTrue(fields().isEmpty())
        assertEquals(1, buttons(R.string.lab_review_flow_skip).size)
    }

    @Test fun aDeletionKeepsItsAcceptButton() {
        show(typeToAccept = true)
        assertTrue(hasText("Why yo"))
        assertEquals(1, buttons(R.string.accept).size)
        assertTrue("Nothing to type for a deletion", fields().isEmpty())
        click(R.string.accept)
        assertEquals(listOf(deletion), revision.acceptedEdits)
    }

    @Test fun typingTheChangeAcceptsItAndMovesOn() {
        show(typeToAccept = true)
        click(R.string.accept) // the deletion
        assertTrue(hasText("Why amigos"))
        assertTrue("The field stands in for Accept", buttons(R.string.accept).isEmpty())
        val label = activity.getString(R.string.lab_type_to_accept_label, "amigos")
        assertNotNull(field(label))
        assertEquals("Focus goes to the field", true, field(label)?.config?.getOrNull(SemanticsProperties.Focused))
        assertEquals("Skip stays beside the field", 1, buttons(R.string.lab_review_flow_skip).size)

        type(label, "ami")
        assertNull(field(label)?.config?.getOrNull(SemanticsProperties.Error))
        assertFalse(hasText(activity.getString(R.string.lab_type_to_accept_off)))

        type(label, "amix")
        // Short on screen, so it fits beside Skip; screen readers hear where to look.
        assertEquals("Not quite", activity.getString(R.string.lab_type_to_accept_off))
        assertEquals(activity.getString(R.string.lab_type_to_accept_off_spoken),
            field(label)?.config?.getOrNull(SemanticsProperties.Error))
        assertTrue(hasText(activity.getString(R.string.lab_type_to_accept_off)))
        assertEquals(listOf(deletion), revision.acceptedEdits)

        type(label, "amig")
        assertNull("Fixing the character clears the error", field(label)?.config?.getOrNull(SemanticsProperties.Error))

        type(label, "amigos")
        assertEquals("Accepted through the same Accept action", listOf(1, 2), accepted)
        assertEquals(setOf(deletion, fix), revision.acceptedEdits.toSet())
        assertTrue("Moves on to the next change", hasText("Why cinema"))
        assertFalse(hasText("Why amigos"))
        val next = field(activity.getString(R.string.lab_type_to_accept_label, "cinema"))
        assertNotNull(next)
        assertEquals("The next change starts empty", "", next?.config?.getOrNull(SemanticsProperties.EditableText)?.text)

        type(activity.getString(R.string.lab_type_to_accept_label, "cinema"), "cinema")
        assertEquals(listOf(1, 2, 3), accepted)
        assertFalse("The walk ends", hasText("Why cinema"))
        assertTrue(fields().isEmpty())
    }

    @Test fun skipStillMovesOnWithoutAccepting() {
        show(typeToAccept = true)
        click(R.string.lab_review_flow_skip) // the deletion
        assertTrue(hasText("Why amigos"))
        type(activity.getString(R.string.lab_type_to_accept_label, "amigos"), "ami")
        click(R.string.lab_review_flow_skip)
        assertTrue(hasText("Why cinema"))
        assertTrue(revision.acceptedEdits.isEmpty())
        assertTrue(accepted.isEmpty())
    }

    /** About a second of frames: past every animation and the correct-answer hold. */
    private fun settle() {
        repeat(SETTLE_FRAMES) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frameTime += 16_000_000L
            frames.sendFrame(frameTime)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun fields() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }

    private fun field(label: String) = fields().singleOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun type(label: String, value: String) {
        val action = requireNotNull(field(label)) { "No field labelled $label" }.config[SemanticsActions.SetText].action
        assertTrue(requireNotNull(action).invoke(AnnotatedString(value)))
        settle()
    }

    private fun buttons(id: Int) = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(id) } == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun click(id: Int) {
        val action = buttons(id).single().config[SemanticsActions.OnClick].action
        assertTrue(requireNotNull(action).invoke())
        settle()
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
}
