package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.testing.OwnComposition
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
    private val compose = OwnComposition(activity, height = 1920, exactWidth = false)

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
        compose.dispose()
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
        compose.show(SETTLE_FRAMES) {
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
            // Rewordings first: the rewording is open on arrival.
            assertTrue("$host opens on the rewording", hasText("Why cinema"))
            assertFalse(hasText("Why yo"))
            click(R.string.accept)
            assertEquals("$host accepted the rewording", listOf(rewording), revision.acceptedEdits)
            assertTrue("$host moves on to the fixes, in reading order", hasText("Why yo"))
            assertFalse(hasText("Why cinema"))
            click(R.string.accept)
            assertTrue("$host moves to the next fix", hasText("Why amigos"))
            click(R.string.accept)
            assertFalse("$host clears the selection when done", hasText("Why amigos"))
            assertTrue(buttons(R.string.accept).isEmpty())
            click(host.applyLabel)
        }
        assertEquals(Host.entries.size, applied)
    }

    @Test fun skipMovesOnAndLeavesTheChangeToTapLater() {
        show(Host.Popup)
        click(R.string.lab_review_flow_skip)
        assertTrue(hasText("Why yo"))
        assertTrue(revision.acceptedEdits.isEmpty())
        click(R.string.lab_review_flow_skip)
        click(R.string.lab_review_flow_skip)
        assertFalse("Skipping the last change ends the walk", hasText("Why amigos"))
        assertTrue(revision.acceptedEdits.isEmpty())
        // The skipped deletion is still a change to tap.
        customAction(activity.getString(R.string.change_why_remove, "yo"))
        assertTrue(hasText("Why yo"))
    }

    @Test fun undoGoesBackToTheChangeItBringsBack() {
        show(Host.Popup)
        click(R.string.accept)
        assertTrue(hasText("Why yo"))
        click(R.string.undo)
        assertTrue(revision.acceptedEdits.isEmpty())
        assertTrue("Undo reopens the rewording", hasText("Why cinema"))
    }

    @Test fun tappingAnotherChangeJumpsThereAndAcceptContinuesFromIt() {
        show(Host.Popup)
        customAction(activity.getString(R.string.change_why_replace, "amigo", "amigos"))
        assertTrue(hasText("Why amigos"))
        assertFalse(hasText("Why cinema"))
        click(R.string.accept)
        // Past the end, the walk wraps to the first open change.
        assertTrue(hasText("Why cinema"))
    }

    @Test fun screenReadersHearThePositionAndCanSkip() {
        show(Host.Popup)
        val spoken = activity.getString(R.string.lab_review_flow_spoken,
            activity.getString(R.string.lab_review_flow_position, 1, 3),
            activity.getString(R.string.change_becomes, "cine", "cinema"))
        assertTrue("Heard: ${descriptions()}", descriptions().contains(spoken))
        customAction(activity.getString(R.string.lab_review_flow_skip_action))
        assertTrue(hasText("Why yo"))
        // The deletion's own actions stay on the text.
        assertTrue(customActions().any { it == activity.getString(R.string.change_remove, "yo") })
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

    private fun settle() = compose.settle(SETTLE_FRAMES)

    private fun nodes(): List<SemanticsNode> = compose.nodes()

    private companion object {
        /** As long as this test waited before it had its own clock: 1200 ms. */
        const val SETTLE_FRAMES = 75
    }
}
