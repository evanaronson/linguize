package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionScreenTest {
    @Test
    fun missingRefreshDropsSavedTextAndKeepsIncompleteDeletionRetry() = partialDeleteCanBeRetried(false)

    @Test
    fun failedRefreshDropsSavedTextAndKeepsIncompleteDeletionRetry() = partialDeleteCanBeRetried(true)

    private fun partialDeleteCanBeRetried(refreshFails: Boolean) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val detail = SessionRecording("Saved before", SessionContext(
            Opening(Origin.Menu, null, "ca", SessionSettings("English", "moderate", "both", "gemini", "m", "p")),
            "1", "device"), 1, newId = { "check" }).close(2, null)
        val rowExists = AtomicBoolean(true)
        val deletes = AtomicInteger()
        val history = object : HistoryStore by SqliteHistoryStore(activity, "unused") {
            override suspend fun detail(id: String): SessionDetailResult = when {
                rowExists.get() -> SessionDetailResult.Found(detail)
                refreshFails -> SessionDetailResult.Failed
                else -> SessionDetailResult.Missing
            }
            override suspend fun delete(id: String): Boolean {
                rowExists.set(false)
                // The row is gone, but the first checkpoint is busy. Retrying is idempotent.
                return deletes.incrementAndGet() > 1
            }
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(activity.packageManager), scope, {})
        var navigatedBack = false
        // Own frame delivery: Robolectric resets Choreographer between tests, while
        // Compose's default Android frame dispatcher can outlive an earlier window.
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { SessionScreen(model, onDeleted = { navigatedBack = true }) } }
        }
        var frameTime = 0L
        fun settle(view: View) {
            fun flush() {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                var delivered = 0
                while (recomposer.hasPendingWork || frames.hasAwaiters) {
                    check(delivered++ < 120) { "Detail composition did not become idle" }
                    frameTime += 16_666_667L
                    frames.sendFrame(frameTime)
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            flush()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1920)
            flush()
        }
        try {
            model.load(detail.session.id)
            waitFor { model.page is SessionPage.Shown }
            activity.setContentView(root)
            settle(root)
            assertTrue(hasText(root, "Saved before"))

            model.delete()
            waitFor { model.deletion == SessionDeletion.Failed }
            model.load(detail.session.id) // HomeActivity.onResume refreshes the open detail.
            waitFor { model.page == if (refreshFails) SessionPage.Failed else SessionPage.Missing }
            settle(root)
            assertFalse(hasText(root, "Saved before"))
            assertTrue(hasText(root, activity.getString(R.string.delete_check_failed)))

            // Exercise the remaining UI, including its existing confirmation flow.
            clickText(root, activity.getString(R.string.delete))
            settle(root)
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            val dialogRoot = requireNotNull(dialog.window).decorView
            settle(dialogRoot)
            clickText(dialogRoot, activity.getString(R.string.delete))
            waitFor { model.deletion == SessionDeletion.Deleted }
            settle(root)
            assertEquals(2, deletes.get())
            assertTrue(navigatedBack)
            assertFalse(hasText(root, "Saved before"))
        } finally {
            scope.cancel()
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun clickText(root: View, text: String) {
        val node = nodes(root).single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun hasText(root: View, text: String): Boolean = nodes(root).any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun nodes(root: View): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for detail")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
