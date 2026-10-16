package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
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

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun withheldCorrectionsHaveARetryAndNoMisleadingNaturalnessClaim() {
        val result = interpret("haurem de experimentat", Verdict(Verdict.Status.Ok,
            hasErrors = true, corrected = "haurem d'experimentar")) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Fix), result.withheld)
        show(result)
        assertTrue(hasText(R.string.fixes_withheld))
        assertFalse(hasText(R.string.sounds_natural))
        clickRetry()
        assertEquals(1, retries)
    }

    @Test fun withheldRewordingsDoNotHideASuccessfulCorrectnessCheck() {
        val result = (interpret("Bon dia", Verdict(Verdict.Status.Ok)) as CheckResult.Reviewed)
            .copy(withheld = setOf(EditKind.Natural))
        show(result)
        assertTrue(hasText(R.string.nothing_to_fix))
        assertTrue(hasText(R.string.rewordings_withheld))
        clickRetry()
        assertEquals(1, retries)
    }

    @Test fun successfulChecksKeepTheirExistingResultWithoutRetry() {
        show(interpret("Bon dia", Verdict(Verdict.Status.Ok)) as CheckResult.Reviewed)
        assertTrue(hasText(R.string.looks_good))
        assertFalse(hasText(R.string.retry))
    }

    private fun show(result: CheckResult.Reviewed) {
        activity.setContent {
            MaterialTheme {
                ReviewContent(result, emptyList(), CardActions(onCopy = { _, _ -> }, review = null,
                    onRetry = { retries++ }, onOpenSettings = null, onSettle = { _, _ -> }))
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

    private fun clickRetry() {
        val action = nodes().single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == activity.getString(R.string.retry) } == true &&
                it.config.getOrNull(SemanticsActions.OnClick) != null
        }.config[SemanticsActions.OnClick].action
        assertTrue(requireNotNull(action).invoke())
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
