package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Lab, Leave as written, on the shared card: the last button of a clean check from another app,
 * and of a review whose every change was skipped. Hosts that save nothing (Chat, Notes) pass no
 * action and show no button. Each case is a fresh composition on its own frame clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeaveAsWrittenCardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView
    private val frames = BroadcastFrameClock()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(uiScope.coroutineContext)
    private var frameTime = 0L

    private val text = "Ayer fui al cine con mis amigo"
    private val amigo = text.indexOf("amigo")
    private val fix = Edit(1, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos")
    private val reviewed = CheckResult.Reviewed(Revision(text, listOf(fix)), listOf(EditKind.Fix, EditKind.Natural))
    private val clean = CheckResult.Reviewed(Revision("Com estàs?", emptyList()), listOf(EditKind.Fix, EditKind.Natural))
    private val state = mutableStateOf<CardState>(CardState.Done(clean))
    private var left = 0
    private var replaced = 0

    @After fun tearDown() {
        recomposer.cancel()
        uiScope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun update(change: (Revision) -> Revision) {
        val done = state.value as CardState.Done
        val result = done.result as CheckResult.Reviewed
        state.value = done.copy(result = result.copy(revision = change(result.revision)))
    }

    private fun show(result: CheckResult.Reviewed, leave: Boolean = true) {
        state.value = CardState.Done(result)
        val actions = CardActions(
            onCopy = { _, _ -> },
            review = ReviewActions(
                onAccept = { id -> update { it.accept(id) } },
                onAcceptAll = { kind -> update { it.acceptAll(kind) } },
                onUndo = { update { it.undo() } },
                onApply = { replaced++ },
                applyLabel = R.string.apply,
            ),
            onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
            onLeaveAsWritten = if (leave) ({ left++ }) else null,
        )
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContentView(ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { ResultCard(state.value, actions, Modifier.fillMaxWidth().heightIn(max = 900.dp)) } }
        })
        settle()
    }

    @Test fun aCleanCheckFromAnotherAppEndsInLeaveAsWritten() {
        show(clean)
        assertTrue(hasText(activity.getString(R.string.looks_good)))
        assertEquals("One button: Leave as written", 1, buttons(R.string.lab_leave_as_written).size)
        assertEquals("Leave as written", activity.getString(R.string.lab_leave_as_written))
        click(R.string.lab_leave_as_written)
        assertEquals(1, left)
    }

    @Test fun hostsThatSaveNothingShowNoButton() {
        show(clean, leave = false)
        assertTrue(hasText(activity.getString(R.string.looks_good)))
        assertTrue(buttons(R.string.lab_leave_as_written).isEmpty())
    }

    @Test fun aReviewWithEveryChangeSkippedEndsInLeaveAsWritten() {
        show(reviewed)
        assertTrue("Not while a change is still open", buttons(R.string.lab_leave_as_written).isEmpty())
        click(R.string.lab_review_flow_skip)
        assertEquals(1, buttons(R.string.lab_leave_as_written).size)
        assertTrue("Nothing to replace", buttons(R.string.apply).isEmpty())
        click(R.string.lab_leave_as_written)
        assertEquals(1, left)
        assertEquals(0, replaced)
    }

    @Test fun withAChangeAcceptedTheLastButtonIsReplace() {
        show(reviewed)
        click(R.string.accept)
        assertEquals(1, buttons(R.string.apply).size)
        assertTrue(buttons(R.string.lab_leave_as_written).isEmpty())
    }

    private fun settle() {
        repeat(64) {
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
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }
}
