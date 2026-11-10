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
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
/** The saved check: its order and the readings inside its Meaning section (the separate Assumptions page is gone). */
class SessionAssumptionsScreenTest {
    @Test
    fun summaryCountsAnsweredAssumptionsOnlyOnce() = withScreen {
        load(SessionDetailResult.Found(detail))

        // Lab, Meaning: collapsed, only the reading nobody answered is counted.
        val one = activity.resources.getQuantityString(R.plurals.lab_meaning_lands_assumptions, 1, 1)
        assertTrue(hasText(one))
        assertFalse(hasText(activity.resources.getQuantityString(R.plurals.lab_meaning_lands_assumptions, 2, 2)))
        assertFalse(hasText("Catalan speakers"))
    }

    @Test
    fun theSavedMeaningOpensInPlaceWithEveryReadingAndNothingToChange() = withScreen {
        load(SessionDetailResult.Found(detail))

        val header = nodes().single {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(R.string.meaning) } == true
        }
        assertEquals(activity.getString(R.string.lab_expand_pattern_collapsed), header.config[SemanticsProperties.StateDescription])
        assertEquals(activity.getString(R.string.lab_meaning_lands_show), header.config[SemanticsActions.OnClick].label)
        assertTrue(header.config[SemanticsActions.OnClick].action!!.invoke())
        settle()

        assertTrue(hasText(activity.getString(R.string.assumptions)))
        assertTrue(hasText(activity.getString(R.string.assumptions_description_past)))
        assertTrue(hasText("Person"))
        assertTrue(hasText("Catalan speakers"))
        assertTrue(hasText("Tomorrow"))
        assertFalse("A past check offers no clarify", hasText(activity.getString(R.string.not_what_you_mean)))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Clarify") == true })
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
        // Historical navigation label; its absence no longer needs a production string resource.
        assertFalse("The readings are here, not behind a link", hasText("Assumptions, 2"))
    }

    @Test
    fun beforeAfterAndChangesComeFirstThenTheReadings() = withScreen {
        load(SessionDetailResult.Found(detail))

        val order = listOf(R.string.history_before, R.string.history_after, R.string.history_suggestions,
            R.string.meaning, R.string.delete_this_check).map { activity.getString(it) }
        // The page is one column, so the semantics tree lists its parts top to bottom.
        val all = nodes()
        val positions = order.map { text ->
            all.indexOfFirst { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text.startsWith(text) } == true }
        }
        assertTrue("$order at $positions", positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun savedClarificationsKeepTheirTopicAndRemainReadOnly() = withScreen {
        load(SessionDetailResult.Found(detail))
        openMeaning()

        assertTrue(hasText("Person"))
        assertTrue(hasText("Catalan speakers"))
        assertTrue(hasText(activity.getString(R.string.your_clarification)))
        assertFalse(hasText(activity.getString(R.string.remove_clarification)))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
        assertFalse(nodes().any {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.clarify_about, "Person")
        })
    }

    @Test
    fun loadingAndFailedRefreshRemainVisibleAndRetryRestoresSavedReadings() = withScreen {
        assertTrue(nodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.getString(R.string.loading_history)) == true
        })
        load(SessionDetailResult.Found(detail))
        openMeaning()
        assertTrue(hasText("Catalan speakers"))
        assertTrue(hasText("Tomorrow"))

        load(SessionDetailResult.Failed)
        assertTrue(hasText(activity.getString(R.string.history_load_failed)))
        assertFalse(hasText("Catalan speakers"))
        result.set(SessionDetailResult.Found(detail))
        clickText(activity.getString(R.string.retry))
        waitFor { model.page is SessionPage.Shown }
        settle()
        openMeaning()
        assertEquals(3, reads.get())
        assertTrue(hasText("Catalan speakers"))
        assertTrue(hasText("Tomorrow"))
    }

    @Test
    fun removedCheckReplacesTheOpenAssumptionsWithMissingState() = withScreen {
        load(SessionDetailResult.Found(detail))
        openMeaning()
        assertTrue(hasText("Catalan speakers"))

        load(SessionDetailResult.Missing)
        assertTrue(hasText(activity.getString(R.string.check_was_deleted)))
        assertFalse(hasText("Catalan speakers"))
        assertFalse(hasText(activity.getString(R.string.retry)))
    }

    @Test
    fun acknowledgedDeletionCannotLeaveTheAssumptionsPageBlank() = withScreen {
        load(SessionDetailResult.Found(detail))
        openMeaning()
        model.delete()
        waitFor { model.deletion == SessionDeletion.Deleted }
        settle()

        assertTrue(hasText(activity.getString(R.string.check_was_deleted)))
        assertFalse(hasText("Catalan speakers"))
    }

    @Test
    fun unavailableReplayShowsTheTextWithoutInventingReadings() = withScreen {
        val damaged = detail.copy(session = detail.session.copy(
            attempts = detail.session.attempts.map { it.copy(raw = "not JSON") },
        ))
        load(SessionDetailResult.Found(damaged))
        openMeaning()

        // An answer that can't be read back has no Meaning section; what was written still shows.
        assertTrue(hasText("Com estas?"))
        assertFalse(hasText("Catalan speakers"))
        assertFalse(hasText(activity.getString(R.string.meaning)))
    }

    private fun withScreen(test: Fixture.() -> Unit) {
        val fixture = Fixture()
        try {
            fixture.test()
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val prompt = Prompt("", "{}")
        val detail = SessionRecording("Com estas?", SessionContext(
            Opening(Origin.Menu, null, "ca", SessionSettings("English", "moderate", "both", "gemini", "m", "p")),
            "1", "device"), 1).let { recording ->
            val raw = """{"status":"ok","language":"Catalan","has_errors":true,"corrected":"Com estàs?","assumptions":[{"about":"Person","assumed":"One person"},{"about":"Time","assumed":"Tomorrow"}]}"""
            val reviewed = interpret("Com estas?", prompt.parseVerdict(raw)) as CheckResult.Reviewed
            recording.attempt(2, listOf(Settled("Person", "Catalan speakers")), raw, null, null,
                reviewed.revision, null, Verdict.Status.Ok)
            val accepted = reviewed.revision.accept(reviewed.revision.edits.first().id)
            recording.changed(accepted)
            recording.close(3, accepted.workingText)
        }
        val result = AtomicReference<SessionDetailResult>(SessionDetailResult.Found(detail))
        val reads = AtomicInteger()
        private val history = object : HistoryStore by SqliteHistoryStore(activity, "unused") {
            override suspend fun detail(id: String): SessionDetailResult {
                reads.incrementAndGet()
                return result.get()
            }
            override suspend fun delete(id: String) = true
        }
        val model = SessionViewModel(history, { prompt }, AppLabels(activity.packageManager), scope, {})
        // Drive frames explicitly so earlier Robolectric windows cannot retain the frame dispatcher.
        private val frames = BroadcastFrameClock()
        private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        private val recomposer = Recomposer(uiScope.coroutineContext)
        private val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent {
                MaterialTheme {
                    SessionScreen(model, onDeleted = {})
                }
            }
        }
        private var frameTime = 0L

        init {
            uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
            activity.setContentView(root)
            settle()
        }

        fun load(value: SessionDetailResult) {
            result.set(value)
            model.load(detail.session.id)
            waitFor {
                when (value) {
                    SessionDetailResult.Failed -> model.page == SessionPage.Failed
                    SessionDetailResult.Missing -> model.page == SessionPage.Missing
                    is SessionDetailResult.Found -> model.page is SessionPage.Shown
                }
            }
            settle()
        }

        fun settle() {
            // A loading indicator keeps requesting frames; a fixed interval allows it to animate.
            fun flush() = repeat(6) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
            }
            flush()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            flush()
        }

        fun clickText(text: String) {
            val node = nodes().single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }

        /** Opens the saved check's Meaning section, where the readings now live, if it's closed. */
        fun openMeaning() {
            val header = nodes().singleOrNull {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(R.string.meaning) } == true
            } ?: return
            if (header.config.getOrNull(SemanticsProperties.StateDescription) == activity.getString(R.string.lab_expand_pattern_collapsed)) {
                assertTrue(header.config[SemanticsActions.OnClick].action!!.invoke())
                settle()
            }
        }

        fun hasText(text: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }

        fun nodes(): List<SemanticsNode> {
            fun owner(view: View): SemanticsOwner? {
                val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                if (method != null) return method.invoke(view) as SemanticsOwner
                if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
                return null
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return flatten(requireNotNull(owner(root)).rootSemanticsNode)
        }

        fun waitFor(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for saved assumptions" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
        }

        fun close() {
            scope.cancel()
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }
}
