package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithheldReviewTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView
    private var retries = 0
    private var copied: Pair<String, EditKind>? = null

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun withheldCorrectionsExplainRecoveryWithoutAMisleadingNaturalnessClaim() {
        val result = interpret("haurem de experimentat", Verdict(Verdict.Status.Ok,
            hasErrors = true, corrected = "haurem d'experimentar")) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Fix), result.withheld)
        show(result)
        assertTrue(hasText(R.string.fixes_withheld))
        assertTrue(hasText(R.string.withheld_detail))
        assertFalse(hasText(R.string.nothing_to_fix))
        assertFalse(hasText(R.string.sounds_natural))
        clickText(R.string.check_again)
        assertEquals(1, retries)
    }

    @Test fun withheldRewordingsDoNotHideASuccessfulCorrectnessCheck() {
        val result = (interpret("Bon dia", Verdict(Verdict.Status.Ok)) as CheckResult.Reviewed)
            .copy(withheld = setOf(EditKind.Natural))
        show(result)
        assertTrue(hasText(R.string.nothing_to_fix))
        assertTrue(hasText(R.string.rewordings_withheld))
        clickText(R.string.check_again)
        assertEquals(1, retries)
    }

    @Test fun bothWithheldKindsHaveOneNoticeAndOneRecoveryAction() {
        val result = (interpret("Bon dia", Verdict(Verdict.Status.Ok)) as CheckResult.Reviewed)
            .copy(withheld = setOf(EditKind.Fix, EditKind.Natural))
        show(result)
        assertTrue(hasText(R.string.changes_withheld))
        assertTrue(hasText(R.string.withheld_detail))
        assertFalse(hasText(R.string.fixes_withheld))
        assertFalse(hasText(R.string.rewordings_withheld))
        assertFalse(hasText(R.string.nothing_to_fix))
        assertFalse(hasText(R.string.sounds_natural))
        assertFalse(hasText(R.string.looks_good))
        assertEquals(1, buttons(R.string.check_again).size)
        assertFalse(hasText(R.string.retry))
        clickText(R.string.check_again)
        assertEquals(1, retries)
    }

    @Test fun recoveryStaysBesideItsExplanationBeforeLongSuggestionsInACompactCard() {
        val result = partialFix("Com estas?\n" + "Bon dia.\n".repeat(50))
        show(result)
        val detail = textNode(activity.getString(R.string.withheld_detail))
        val again = buttons(R.string.check_again).single()
        val preview = nodes().first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Com estàs?\n") } == true
        }
        val detailBottom = detail.positionInRoot.y + detail.size.height
        val againBottom = again.positionInRoot.y + again.size.height
        val density = activity.resources.displayMetrics.density
        assertTrue("Recovery follows its explanation", again.positionInRoot.y >= detailBottom)
        assertTrue("Recovery stays next to the explanation", again.positionInRoot.y - detailBottom <= 16f * density)
        assertTrue("Long suggestions cannot separate the notice from recovery", againBottom <= preview.positionInRoot.y)
        assertTrue("Recovery is visible in the 320dp viewport", again.boundsInRoot.height > 0f &&
            againBottom <= 320f * density)
        assertFalse(hasText(R.string.accept_all))
        customAction(R.string.copy_with_fixes)
        assertEquals(result.revision.preview(EditKind.Fix).text to EditKind.Fix, copied)
        clickText(R.string.check_again)
        assertEquals("Read-only live checks can still check again", 1, retries)
    }

    @Test fun availableSuggestionsKeepTheirCopyAndAcceptActions() {
        val accepted = mutableListOf<Int>()
        val acceptedKinds = mutableListOf<EditKind>()
        val result = partialFix()
        show(result, ReviewActions(onAccept = { accepted += it }, onAcceptAll = { acceptedKinds += it },
            onUndo = {}, onApply = {}))
        customAction(R.string.copy_with_fixes)
        assertEquals("Com estàs?" to EditKind.Fix, copied)
        customAction(activity.getString(R.string.change_replace, "estas", "estàs"))
        clickText(R.string.accept_all)
        assertEquals(listOf(0), accepted)
        assertEquals(listOf(EditKind.Fix), acceptedKinds)
        assertTrue(hasText(R.string.fixes_withheld))
    }

    @Test fun acceptedAvailableChangesKeepUndoApplyAndTheIncompleteNotice() {
        val result = partialFix().let { it.copy(revision = it.revision.acceptAll(EditKind.Fix)) }
        var undone = 0
        var applied = 0
        show(result, ReviewActions(onAccept = {}, onAcceptAll = {}, onUndo = { undone++ }, onApply = { applied++ }))
        assertTrue(hasText(R.string.fixes_withheld))
        assertTrue(hasText(R.string.check_again))
        assertTrue(hasText(R.string.all_accepted))
        assertFalse(hasText(R.string.looks_good))
        clickText(R.string.undo)
        clickText(R.string.apply)
        assertEquals(1, undone)
        assertEquals(1, applied)
    }

    @Test fun successfulChecksKeepTheirExistingResultWithoutRecovery() {
        show(interpret("Bon dia", Verdict(Verdict.Status.Ok)) as CheckResult.Reviewed)
        assertTrue(hasText(R.string.looks_good))
        assertFalse(hasText(R.string.withheld_detail))
        assertFalse(hasText(R.string.check_again))
        assertFalse(hasText(R.string.retry))
    }

    private fun partialFix(text: String = "Com estas?") = CheckResult.Reviewed(
        revision = Revision(text, listOf(Edit(0, EditKind.Fix, 4, 9, "estas", "estàs", "Missing accent"))),
        kinds = listOf(EditKind.Fix, EditKind.Natural),
        withheld = setOf(EditKind.Fix),
    )

    private fun show(result: CheckResult.Reviewed, review: ReviewActions? = null) {
        activity.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.width(280.dp).height(320.dp).verticalScroll(rememberScrollState())) {
                        Suggestions(result, emptyList(), CardActions(onCopy = { text, kind -> copied = text to kind },
                            review = review, onRetry = { retries++ }, onOpenSettings = null, onSettle = { _, _ -> }))
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun hasText(id: Int) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(id) } == true
    }

    private fun textNode(text: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun buttons(id: Int) = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(id) } == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun clickText(id: Int) {
        val action = buttons(id).single().config[SemanticsActions.OnClick].action
        assertTrue(requireNotNull(action).invoke())
    }

    private fun customAction(id: Int) = customAction(activity.getString(id))

    private fun customAction(label: String) {
        val action = nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            .first { it.label == label }
        assertTrue(action.action())
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
