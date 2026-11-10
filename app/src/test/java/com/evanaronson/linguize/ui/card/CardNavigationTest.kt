package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.understand.UnderstandState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** Exercise both scroll hosts and the separate clipboard job through the rendered controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardNavigationTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    // Own the clock and recomposer: Robolectric can replace Choreographer between tests,
    // while Compose's default Android dispatcher still holds the previous instance.
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = ComposeView(activity)
    private var frameTime = 0L
    private val actions = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
        onOpenSettings = null, onSettle = { _, _ -> })

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        root.setParentCompositionContext(recomposer)
        activity.setContentView(root)
    }

    @After fun tearDown() {
        root.disposeComposition()
        recomposer.cancel()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun clipboardFooterRemainsAvailableAcrossEveryCheckOutcomeAndMeaning() {
        val state = mutableStateOf<CardState>(CardState.Loading("Bon dia"))
        var opened = 0
        root.setContent {
            MaterialTheme {
                ResultCard(state.value, actions, onUnderstandCopiedText = { opened++ })
            }
        }
        val states = listOf(
            CardState.Loading("Bon dia"),
            CardState.Failed(CheckFailure.Reason.Offline, null),
            CardState.Done(CheckResult.Unclear, sourceText = "Bon dia"),
            CardState.Done(CheckResult.WrongLanguage("Spanish", "Catalan")),
            CardState.Done(reviewed(lines = 1)),
        )
        states.forEach { next ->
            state.value = next
            settle()
            clickText(R.string.understand_copied_text)
        }
        assertEquals(states.size, opened)
        clickText(R.string.meaning)
        clickText(R.string.understand_copied_text)
        assertEquals(states.size + 1, opened)
    }

    @Test fun clipboardBackIsOnlyOfferedForARetainedCheckAcrossEveryUnderstandState() {
        val state = mutableStateOf<UnderstandState>(UnderstandState.ReadingClipboard)
        val retained = mutableStateOf(false)
        var returned = 0
        root.setContent {
            MaterialTheme {
                UnderstandCard(state.value, onRetry = {},
                    onBackToCheck = if (retained.value) ({ returned++ }) else null)
            }
        }
        val states = listOf(
            UnderstandState.ReadingClipboard,
            UnderstandState.Message(unavailable = false),
            UnderstandState.Message(unavailable = true),
            UnderstandState.Loading("Bon dia"),
            UnderstandState.Failed("Bon dia", CheckFailure(CheckFailure.Reason.Offline)),
            UnderstandState.Done("Bon dia", Understood("Good morning", emptyList())),
        )
        states.forEach { next ->
            state.value = next
            retained.value = false
            settle()
            assertTrue("A standalone clipboard job has no check to return to", backButtons().isEmpty())
            retained.value = true
            settle()
            assertTrue(nodes().any { it.config.contains(SemanticsProperties.Heading) &&
                containsText(it, activity.getString(R.string.understand_copied_text)) })
            clickBack()
        }
        assertEquals(states.size, returned)
    }

    @Test fun meaningOpensInPlaceSoSystemBackGoesStraightToTheHost() {
        var hostBack = 0
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { hostBack++ }
        })
        root.setContent {
            MaterialTheme { ResultCard(CardState.Done(reviewed(lines = 1)), actions) }
        }
        settle()
        clickText(R.string.meaning)
        // Open in place: the suggestions are still there, and there is no page to go back from.
        assertTrue(nodes().any { containsText(it, activity.resources.getQuantityString(R.plurals.fix_count, 1, 1)) })
        assertTrue(backButtons().isEmpty())
        assertTrue(nodes().any { containsText(it, "The writer greets everyone.") })
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("The card has no Back level of its own", 1, hostBack)
    }

    @Test fun systemBackReturnsToARetainedCheckButClosesAStandaloneClipboardJob() {
        var returned = 0
        var closed = 0
        val retained = mutableStateOf(true)
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closed++ }
        })
        root.setContent {
            MaterialTheme {
                UnderstandCard(UnderstandState.Message(false), onRetry = {},
                    onBackToCheck = if (retained.value) ({ returned++ }) else null)
            }
        }
        settle()
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, returned)
        assertEquals(0, closed)
        retained.value = false
        settle()
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, returned)
        assertEquals(1, closed)
    }

    @Test fun nonReviewedResultsShowTheAcceptedTextAndWorkingUndoAndApplyControls() {
        val result = mutableStateOf<CheckResult>(CheckResult.Unclear)
        val revision = mutableStateOf<Revision?>(null)
        var applied = 0
        val review = ReviewActions(onAccept = {}, onAcceptAll = {},
            onUndo = { revision.value = revision.value?.undo() }, onApply = { applied++ })
        root.setContent {
            MaterialTheme {
                ResultCard(CardState.Done(result.value, retainedRevision = revision.value),
                    CardActions(onCopy = { _, _ -> }, review = review, onRetry = {},
                        onOpenSettings = null, onSettle = { _, _ -> }))
            }
        }
        for (outcome in listOf(CheckResult.Unclear, CheckResult.WrongLanguage("Catalan", "English"))) {
            result.value = outcome
            revision.value = Revision("Bon dia", listOf(Edit(0, EditKind.Fix, 0, 3, "Bon", "Bona", null)))
                .acceptAll(EditKind.Fix)
            settle()
            assertTrue(nodes().any { containsText(it, "Bona dia") })
            clickText(R.string.apply)
            clickText(R.string.undo)
            assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                containsText(it, activity.getString(R.string.apply)) })
            assertFalse(nodes().any { containsText(it, "Bona dia") })
        }
        assertEquals(2, applied)
    }

    @Test fun meaningOpensBelowTheFinalActionInTheFloatingCard() = verifyFinalAction(parentScrolls = false)

    @Test fun meaningOpensBelowTheFinalActionInTheHomeScrollContainer() = verifyFinalAction(parentScrolls = true)

    /**
     * Lab, Meaning: the section sits below "1 change accepted, Undo, Replace", so opening it
     * never pushes Replace out of reach, and Replace stays tappable with the section open.
     */
    private fun verifyFinalAction(parentScrolls: Boolean) {
        val result = reviewed()
        var applied = 0
        val revision = result.revision.acceptAll(EditKind.Fix)
        val review = ReviewActions(onAccept = {}, onAcceptAll = {}, onUndo = {}, onApply = { applied++ })
        val withReview = CardActions(onCopy = { _, _ -> }, review = review, onRetry = {},
            onOpenSettings = null, onSettle = { _, _ -> })
        var viewportBounds = Rect.Zero
        root.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val viewport = Modifier.width(320.dp).height(320.dp).onGloballyPositioned {
                        viewportBounds = it.boundsInRoot()
                    }
                    val card = CardState.Done(result.copy(revision = revision))
                    if (parentScrolls) {
                        Column(viewport.verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(600.dp))
                            ResultCard(card, withReview, scrollable = false)
                            Spacer(Modifier.height(100.dp))
                        }
                    } else {
                        ResultCard(card, withReview, viewport)
                    }
                }
            }
        }
        fun visible(node: SemanticsNode) = node.boundsInRoot.let {
            it.height > 0f && it.top >= viewportBounds.top && it.bottom <= viewportBounds.bottom
        }
        fun replace() = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            containsText(it, activity.getString(R.string.apply)) }
        fun meaning() = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            containsText(it, activity.getString(R.string.meaning)) }
        settle()
        scrollToBottom()
        assertTrue("Replace is reachable at the end of the walk", visible(replace()))
        assertTrue("Meaning is reachable", visible(meaning()))
        assertTrue("Meaning comes after the final action", meaning().positionInRoot.y > replace().positionInRoot.y + replace().size.height)
        val replaceTop = replace().positionInRoot.y

        assertTrue(meaning().config[SemanticsActions.OnClick].action!!.invoke())
        settle()
        assertTrue(backButtons().isEmpty())
        assertTrue("The open section is shown", nodes().any { containsText(it, result.meaning.trim()) })
        assertTrue("Opening it never moves Replace down", replace().positionInRoot.y <= replaceTop + 1f)
        assertTrue(meaning().positionInRoot.y > replace().positionInRoot.y + replace().size.height)
        assertTrue(replace().config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(1, applied)
    }

    private fun reviewed(lines: Int = 50): CheckResult.Reviewed {
        val text = "Bon dia.\n".repeat(lines)
        return CheckResult.Reviewed(
            Revision(text, listOf(Edit(0, EditKind.Fix, 0, 3, "Bon", "Bona", null))),
            listOf(EditKind.Fix), meaning = "The writer greets everyone.\n".repeat(lines),
        )
    }

    private fun scrollToBottom() {
        val scroll = nodes().single { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f))
        settle()
    }

    private fun scrollRange() = nodes().single {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }.config[SemanticsProperties.VerticalScrollAxisRange]

    private fun scrollOffset() = scrollRange().value()

    private fun clickText(id: Int) {
        val text = activity.getString(id)
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && containsText(it, text) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun backButtons() = nodes().filter {
        it.config.getOrNull(SemanticsActions.OnClick) != null &&
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.getString(R.string.back)) == true
    }

    private fun clickBack() {
        assertTrue(backButtons().single().config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun containsText(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.children.any { containsText(it, text) }

    private fun settle() {
        // Layout belongs to each frame: a newly placed page wakes its scroll effect, and
        // the resulting scroll needs another layout before visibility can be asserted.
        // Loading has an infinite progress animation, so drive a finite two seconds of
        // frames rather than waiting for all animation awaiters to disappear.
        repeat(120) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            frameTime += 16_666_667L
            frames.sendFrame(frameTime)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            // Equal MeasureSpecs may otherwise let Android skip the Compose layout pass.
            ownerView(root)?.forceLayout()
            root.forceLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun ownerView(view: View): View? {
        if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            ownerView(view.getChildAt(index))?.let { return it }
        }
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
