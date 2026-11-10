package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
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
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Lab, Review flow: read, tap, tap, tap through the card, the same in every host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewFlowCardTest {
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

    /** The popup and Chat scroll inside the card; Notes puts it in a scrolling screen. */
    private enum class Host(val scrollable: Boolean, val applyLabel: Int) {
        Popup(true, R.string.apply),
        Chat(true, R.string.lab_chat_send),
        Notes(false, R.string.apply),
    }

    private val state = mutableStateOf<CardState>(CardState.Done(result))
    private var applied = 0

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private val revision get() = ((state.value as CardState.Done).result as CheckResult.Reviewed).revision

    private fun update(change: (Revision) -> Revision) {
        val done = state.value as CardState.Done
        val reviewed = done.result as CheckResult.Reviewed
        state.value = done.copy(result = reviewed.copy(revision = change(reviewed.revision)))
    }

    private fun show(host: Host) {
        state.value = CardState.Done(result)
        val actions = CardActions(
            onCopy = { _, _ -> },
            review = ReviewActions(
                onAccept = { id -> update { it.accept(id) } },
                onAcceptAll = { kind -> update { it.acceptAll(kind) } },
                onUndo = { update { it.undo() } },
                onApply = { applied++ },
                applyLabel = host.applyLabel,
            ),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
        )
        activity.setContent {
            MaterialTheme {
                if (host.scrollable) {
                    ResultCard(state.value, actions, Modifier.fillMaxWidth().heightIn(max = 600.dp))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ResultCard(state.value, actions, Modifier.fillMaxWidth(), scrollable = false)
                    }
                }
            }
        }
        settle()
    }

    @Test fun theCardWalksEveryChangeInOrderInEveryHost() {
        for (host in Host.entries) {
            show(host)
            // The first change in reading order is open on arrival: the deletion, in its place.
            assertTrue("$host opens on the first change", hasText("Why yo"))
            assertFalse(hasText("Why amigos"))
            click(R.string.accept)
            assertEquals("$host accepted the deletion", listOf(deletion), revision.acceptedEdits)
            assertTrue("$host moves to the next fix", hasText("Why amigos"))
            assertFalse(hasText("Why yo"))
            click(R.string.accept)
            assertTrue("$host moves on to the rewordings", hasText("Why cinema"))
            click(R.string.accept)
            assertFalse("$host clears the selection when done", hasText("Why cinema"))
            assertTrue(buttons(R.string.accept).isEmpty())
            click(host.applyLabel)
        }
        assertEquals(Host.entries.size, applied)
    }

    @Test fun skipMovesOnAndLeavesTheChangeToTapLater() {
        show(Host.Popup)
        click(R.string.lab_review_flow_skip)
        assertTrue(hasText("Why amigos"))
        assertTrue(revision.acceptedEdits.isEmpty())
        click(R.string.lab_review_flow_skip)
        click(R.string.lab_review_flow_skip)
        assertFalse("Skipping the last change ends the walk", hasText("Why cinema"))
        assertTrue(revision.acceptedEdits.isEmpty())
        // The skipped deletion is still a change to tap.
        customAction(activity.getString(R.string.change_why_remove, "yo"))
        assertTrue(hasText("Why yo"))
    }

    @Test fun undoGoesBackToTheChangeItBringsBack() {
        show(Host.Popup)
        click(R.string.accept)
        assertTrue(hasText("Why amigos"))
        click(R.string.undo)
        assertTrue(revision.acceptedEdits.isEmpty())
        assertTrue("Undo reopens the deletion", hasText("Why yo"))
    }

    @Test fun tappingAnotherChangeJumpsThereAndAcceptContinuesFromIt() {
        show(Host.Popup)
        customAction(activity.getString(R.string.change_why_replace, "cine", "cinema"))
        assertTrue(hasText("Why cinema"))
        assertFalse(hasText("Why yo"))
        click(R.string.accept)
        // Past the end, the walk wraps to the first open change.
        assertTrue(hasText("Why yo"))
    }

    @Test fun screenReadersHearThePositionAndCanSkip() {
        show(Host.Popup)
        val spoken = activity.getString(R.string.lab_review_flow_spoken,
            activity.getString(R.string.lab_review_flow_position, 1, 3),
            activity.getString(R.string.change_remove, "yo"))
        assertTrue("Heard: ${descriptions()}", descriptions().contains(spoken))
        customAction(activity.getString(R.string.lab_review_flow_skip_action))
        assertTrue(hasText("Why amigos"))
        // The deletion's own actions stay on the text.
        assertTrue(customActions().any { it == activity.getString(R.string.change_remove, "yo") })
    }

    private fun settle() {
        repeat(2) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun buttons(id: Int) = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(id) } == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun click(id: Int) {
        val action = buttons(id).single().config[SemanticsActions.OnClick].action
        assertTrue(requireNotNull(action).invoke())
        settle()
    }

    private fun customActions() = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
        .map { it.label }

    private fun customAction(label: String) {
        val action = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            .first { it.label == label }
        assertTrue(action.action())
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
