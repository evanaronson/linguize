package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.understand.UnderstandState
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
    private val root get() = activity.window.decorView
    private val actions = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
        onOpenSettings = null, onSettle = { _, _ -> })

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun clipboardFooterRemainsAvailableAcrossEveryCheckOutcomeAndMeaning() {
        val state = mutableStateOf<CardState>(CardState.Loading("Bon dia"))
        var opened = 0
        activity.setContent {
            MaterialTheme {
                ResultCard(state.value, actions, onUnderstandCopiedText = { opened++ })
            }
        }
        val states = listOf(
            CardState.Loading("Bon dia"),
            CardState.Failed(CheckFailure.Reason.Offline, null),
            CardState.Done(CheckResult.Unclear, sourceText = "Bon dia"),
            CardState.Done(CheckResult.WrongLanguage("Spanish", "Catalan")),
            CardState.Done(reviewed()),
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
        activity.setContent {
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

    @Test fun systemBackReturnsFromMeaningBeforeTheHostCanCloseOrApplyTheCheck() {
        var hostBack = 0
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { hostBack++ }
        })
        activity.setContent {
            MaterialTheme { ResultCard(CardState.Done(reviewed()), actions) }
        }
        settle()
        clickText(R.string.meaning)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(0, hostBack)
        assertTrue(backButtons().isEmpty())
        assertTrue(nodes().any { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            containsText(it, activity.getString(R.string.meaning)) })
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("Only Back from the suggestions reaches the host", 1, hostBack)
    }

    @Test fun systemBackReturnsToARetainedCheckButClosesAStandaloneClipboardJob() {
        var returned = 0
        var closed = 0
        val retained = mutableStateOf(true)
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closed++ }
        })
        activity.setContent {
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
        activity.setContent {
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

    @Test fun meaningNavigationRevealsTheHeaderInTheFloatingCard() = verifyNavigation(parentScrolls = false)

    @Test fun meaningNavigationRevealsTheHeaderInTheHomeScrollContainer() = verifyNavigation(parentScrolls = true)

    private fun verifyNavigation(parentScrolls: Boolean) {
        val result = reviewed()
        activity.setContent {
            MaterialTheme {
                if (parentScrolls) {
                    Column(Modifier.width(320.dp).height(320.dp).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(600.dp))
                        ResultCard(CardState.Done(result), actions, scrollable = false)
                        Spacer(Modifier.height(600.dp))
                    }
                } else {
                    ResultCard(CardState.Done(result), actions, Modifier.width(320.dp).height(320.dp))
                }
            }
        }
        settle()
        scrollToBottom()
        assertTrue(scrollOffset() > 0f)
        clickText(R.string.meaning)
        val heading = nodes().first { it.config.contains(SemanticsProperties.Heading) &&
            containsText(it, activity.getString(R.string.meaning)) }
        assertTrue("The page title must be in the viewport", heading.boundsInRoot.height > 0f)
        assertTrue("The back control must be in the viewport", backButtons().single().boundsInRoot.height > 0f)
        if (!parentScrolls) assertEquals(0f, scrollOffset(), 1f)

        scrollToBottom()
        assertTrue(scrollOffset() > 0f)
        clickBack()
        val count = activity.resources.getQuantityString(R.plurals.fix_count, 1, 1)
        val firstLine = nodes().first { containsText(it, count) && it.config.getOrNull(SemanticsProperties.Text) != null }
        assertTrue("Returning reveals the first suggestion section", firstLine.boundsInRoot.height > 0f)
        if (!parentScrolls) assertEquals(0f, scrollOffset(), 1f)
    }

    private fun reviewed(): CheckResult.Reviewed {
        val text = "Bon dia.\n".repeat(50)
        return CheckResult.Reviewed(
            Revision(text, listOf(Edit(0, EditKind.Fix, 0, 3, "Bon", "Bona", null))),
            listOf(EditKind.Fix), meaning = "The writer greets everyone.\n".repeat(50),
        )
    }

    private fun scrollToBottom() {
        val scroll = nodes().single { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f))
        settle()
    }

    private fun scrollOffset() = nodes().single {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }.config[SemanticsProperties.VerticalScrollAxisRange].value()

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
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
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
