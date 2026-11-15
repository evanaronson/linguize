package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.ui.history.SAVED_CHANGES_TAG
import com.evanaronson.linguize.ui.history.SavedSuggestions
import com.evanaronson.linguize.ui.history.savedSuggestions
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
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * One change view: the live card, a past chat message and a saved check all write each change
 * in place (old words struck through, then the new, under the card's underline), and all show
 * the explanation of the tapped (or current) change only. Each test composes afresh and drives
 * its own frame clock, so nothing waits on a shared one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OneChangeViewTest {
    private val text = "Ayer yo fui al cine con mis amigo"
    private val amigo = text.indexOf("amigo")
    private val deletion = Edit(1, EditKind.Fix, 5, 8, "yo ", "", "Why yo")
    private val fix = Edit(2, EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos")
    private val rewording = Edit(3, EditKind.Natural, text.indexOf("cine"), text.indexOf("cine") + 4,
        "cine", "cinema", "Why cinema")
    private val result = CheckResult.Reviewed(Revision(text, listOf(fix, rewording, deletion)),
        listOf(EditKind.Fix, EditKind.Natural))
    private val state = mutableStateOf<CardState>(CardState.Done(result))
    private val revision get() = ((state.value as CardState.Done).result as CheckResult.Reviewed).revision

    private fun update(change: (Revision) -> Revision) {
        val done = state.value as CardState.Done
        val reviewed = done.result as CheckResult.Reviewed
        state.value = done.copy(result = reviewed.copy(revision = change(reviewed.revision)))
    }

    private val actions = CardActions(
        onCopy = { _, _ -> },
        review = ReviewActions(
            onAccept = { id -> update { it.accept(id) } },
            onAcceptAll = { kind -> update { it.acceptAll(kind) } },
            onUndo = { update { it.undo() } },
            onApply = {},
        ),
        onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
    )

    /** Tall enough that the whole card is on screen, so real taps reach both texts. */
    @Composable private fun Card() = ResultCard(state.value, actions, Modifier.fillMaxWidth().heightIn(max = 3000.dp))

    @Test fun theLiveCardWritesEachPendingChangeAsItsStruckOldWordsThenTheNew() = show({ Card() }) { page ->
        val (fixes, rewordings) = page.passages()
        // The fixes: the deleted word struck in its place, the old word struck before the new one.
        assertEquals("Ayer yo fui al cine con mis amigo amigos", fixes.text.removeSuffix(" ￼"))
        assertEquals(listOf("yo", "amigo"), fixes.struck())
        // The rewordings, on the corrected text: the same.
        assertEquals("Ayer fui al cine cinema con mis amigos", rewordings.text.removeSuffix(" ￼"))
        assertEquals(listOf("cine"), rewordings.struck())
        // Screen readers hear the real text, with each change as an action in words.
        assertTrue(page.descriptions().contains("Ayer fui al cinema con mis amigos"))
        assertTrue(page.actions().contains(page.string(R.string.change_why_replace, "amigo", "amigos")))
    }

    @Test fun selectingAChangeShowsOnlyItsExplanation() = show({ Card() }) { page ->
        // Arrives on the first change, the deletion: its explanation alone.
        assertEquals(listOf("Why yo"), page.reasons())
        page.tapOn("amigos", passage = 0)
        assertEquals(listOf("Why amigos"), page.reasons())
        page.tapOn("cinema", passage = 1)
        assertEquals(listOf("Why cinema"), page.reasons())
        // Tapping the open change closes it.
        page.tapOn("cinema", passage = 1)
        assertTrue(page.reasons().isEmpty())
    }

    @Test fun anAcceptedChangeShowsJustItsNewWordsAndASkippedOneItsOriginal() = show({ Card() }) { page ->
        page.click(R.string.accept)
        assertEquals(listOf(deletion), revision.acceptedEdits)
        // Accepted: what will be sent, plain. The walk is on the next fix.
        assertEquals("Ayer fui al cine con mis amigo amigos", page.passages()[0].text.removeSuffix(" ￼"))
        assertEquals(listOf("amigo"), page.passages()[0].struck())
        assertEquals(listOf("Why amigos"), page.reasons())

        page.click(R.string.lab_review_flow_skip)
        // Skipped: the original word stays, unmarked, in both texts; nothing is struck for it.
        assertEquals("Ayer fui al cine con mis amigo", page.passages()[0].text.removeSuffix(" ￼"))
        assertTrue(page.passages()[0].struck().isEmpty())
        assertEquals("Ayer fui al cine cinema con mis amigo", page.passages()[1].text.removeSuffix(" ￼"))
        assertEquals(listOf("Why cinema"), page.reasons())

        // Tapping the skipped word brings its change back, proposed again.
        page.tapOn("amigo", passage = 0)
        assertEquals(listOf("Why amigos"), page.reasons())
        assertEquals("Ayer fui al cine con mis amigo amigos", page.passages()[0].text.removeSuffix(" ￼"))
    }

    @Test fun aPastChecksRecordIsTheSameViewReadOnlyWithNoList() {
        val passages = recordPassages(text, listOf(
            RecordedChange(EditKind.Fix, 5, 8, "yo ", "", "Why yo"),
            RecordedChange(EditKind.Fix, amigo, amigo + 5, "amigo", "amigos", "Why amigos"),
            RecordedChange(EditKind.Natural, text.indexOf("cine"), text.indexOf("cine") + 4, "cine", "cinema", "Why cinema"),
        ))!!
        show({ ChangeRecord(passages) }) { page ->
            // One text with both kinds, written as the card writes them; no copy button.
            val drawn = page.passages().single()
            assertEquals("Ayer yo fui al cine cinema con mis amigo amigos", drawn.text)
            assertEquals(listOf("yo", "cine", "amigo"), drawn.struck())
            // No list and no reason until a change is tapped; then only that one, with nothing to accept.
            assertTrue(page.reasons().isEmpty())
            page.tapOn("cinema", passage = 0)
            assertEquals(listOf("Why cinema"), page.reasons())
            assertTrue(page.descriptions().contains(page.string(R.string.change_becomes, "cine", "cinema")))
            assertFalse(page.hasText(page.string(R.string.accept)))
            assertFalse(page.hasText(page.string(R.string.lab_review_flow_skip)))
            page.tapOn("amigos", passage = 0)
            assertEquals(listOf("Why amigos"), page.reasons())
        }
    }

    @Test fun historysSavedCheckShowsTheSameViewWithWhatBecameOfEachChange() {
        val original = "Com estas?"
        val saved = Revision(original, listOf(
            Edit(0, EditKind.Fix, 4, 9, "estas", "estàs", why = "Accent marks the verb."),
            Edit(1, EditKind.Fix, 9, 10, "?", "!", why = "An exclamation."),
        ))
        val context = SessionContext(Opening(Origin.Menu, null, "ca",
            SessionSettings("English", "moderate", "both", "gemini", "m", "p")), "1", "device")
        val detail = SessionRecording(original, context, 1).let {
            it.attempt(2, emptyList(), "saved", null, null, saved, null, Verdict.Status.Ok)
            it.changed(saved.accept(0))
            it.close(3, saved.accept(0).workingText)
        }
        val rows = savedSuggestions(detail)
        show({ Column { SavedSuggestions("s", rows, hasFinalText = true, text = original) } }) { page ->
            assertTrue(page.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == SAVED_CHANGES_TAG })
            val drawn = page.passages().single()
            assertEquals("Com estas estàs?!", drawn.text)
            assertEquals(listOf("estas", "?"), drawn.struck())
            // No row per suggestion any more.
            assertTrue(page.nodes().none {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == page.string(R.string.history_show_explanation)
            })
            page.tapOn("estàs", passage = 0)
            assertEquals(listOf("Accent marks the verb."), page.reasons(listOf("Accent marks the verb.", "An exclamation.")))
            assertTrue(page.hasText(page.string(R.string.history_applied)))
            page.why(page.string(R.string.change_why_replace, "?", "!"))
            assertTrue(page.hasText("An exclamation."))
            assertTrue(page.hasText(page.string(R.string.history_not_applied)))
        }
        // A record whose spans no longer fit its text keeps the literal list.
        val damaged = rows.map { it.copy(start = 40, end = 45) }
        show({ Column { SavedSuggestions("s", damaged, hasFinalText = true, text = original) } }) { page ->
            assertTrue(page.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == SAVED_CHANGES_TAG })
            assertEquals(2, page.nodes().count {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == page.string(R.string.history_show_explanation)
            })
        }
    }

    /** One drawn text of the view: what is drawn, and its struck parts. */
    private class Drawn(val node: SemanticsNode, val value: AnnotatedString) {
        val text get() = value.text
        fun struck() = value.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
            .sortedBy { it.start }.map { value.text.substring(it.start, it.end) }
    }

    private inner class Page(val root: ComposeView, val activity: ComponentActivity, val flush: () -> Unit) {
        fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

        fun nodes(merged: Boolean = false): List<SemanticsNode> {
            fun owner(view: View): SemanticsOwner? {
                val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                if (method != null) return method.invoke(view) as SemanticsOwner
                if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
                return null
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val owner = requireNotNull(owner(root))
            return flatten(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }

        /** The view's texts, top to bottom: the ones with a "Why" action per change. */
        fun passages(): List<Drawn> = nodes()
            .filter { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label.startsWith("Why") } == true }
            .map { Drawn(it, it.config[SemanticsProperties.Text].single()) }

        fun hasText(value: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true }

        fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        fun actions() = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.map { it.label }

        /** Which of the reasons show. */
        fun reasons(all: List<String> = listOf("Why yo", "Why amigos", "Why cinema")) = all.filter { hasText(it) }

        fun click(id: Int) {
            val button = nodes(merged = true).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == string(id) } == true &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
            flush()
        }

        fun why(label: String) {
            assertTrue(nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.first { it.label == label }.action())
            flush()
        }

        /** A real tap on the middle of [words] (their last occurrence) in the [passage]'th text. */
        fun tapOn(words: String, passage: Int) {
            val drawn = passages()[passage]
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(drawn.node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
            val at = drawn.text.lastIndexOf(words).also { assertTrue("$words in ${drawn.text}", it >= 0) } + words.length / 2
            val point = drawn.node.positionInRoot + results.single().getBoundingBox(at).center
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 10 else 0,
                    action, point.x, point.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            flush()
        }
    }

    private fun show(content: @Composable () -> Unit, check: (Page) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val frames = BroadcastFrameClock()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(uiScope.coroutineContext)
        uiScope.launch { recomposer.runRecomposeAndApplyChanges() }
        val root = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent { MaterialTheme { content() } }
        }
        var frameTime = 0L
        fun flush() {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            var delivered = 0
            while (recomposer.hasPendingWork || frames.hasAwaiters) {
                check(delivered++ < 600) { "Composition did not become idle" }
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, HEIGHT)
            // Lays out what changed now, so positions and text layouts are current.
            fun measureAndLayout(view: View) {
                view.javaClass.methods.firstOrNull {
                    it.name == "measureAndLayout" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }?.invoke(view, true)
                if (view is ViewGroup) for (index in 0 until view.childCount) measureAndLayout(view.getChildAt(index))
            }
            measureAndLayout(root)
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            activity.setContentView(root)
            flush()
            flush()
            check(Page(root, activity, ::flush))
        } finally {
            root.disposeComposition()
            recomposer.cancel()
            uiScope.cancel()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private companion object {
        /** The page's height in pixels: room for the whole card and its open explanation. */
        const val HEIGHT = 6000
    }
}
