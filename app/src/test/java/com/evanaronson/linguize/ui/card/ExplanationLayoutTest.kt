package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.theme.LinguizeTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplanationLayoutTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun narrowExplanationGivesTheMetersSpaceBelowAccept() = checkMeterBounds(fontScale = 1f)

    @Test fun narrowExplanationGivesTheMetersSpaceBelowAcceptAtLargeFont() = checkMeterBounds(fontScale = 2f)

    @Test fun widerExplanationKeepsAcceptAndScoresClearOfTheProse() = checkMeterBounds(fontScale = 1f, width = 360)

    @Test fun metersSpeakMappedLevels() {
        val cases = listOf(
            Triple(SuggestionAssessment(0, 10), R.string.assessment_low, R.string.assessment_high),
            Triple(SuggestionAssessment(5, 9), R.string.assessment_low, R.string.assessment_high),
            Triple(SuggestionAssessment(6, 8), R.string.assessment_medium, R.string.assessment_medium),
            Triple(SuggestionAssessment(8, 6), R.string.assessment_medium, R.string.assessment_medium),
            Triple(SuggestionAssessment(9, 5), R.string.assessment_high, R.string.assessment_low),
            Triple(SuggestionAssessment(10, 0), R.string.assessment_high, R.string.assessment_low),
        )
        val root = ComposeView(activity).apply {
            setContent {
                LinguizeTheme(dark = false) {
                    Column {
                        cases.forEachIndexed { index, (assessment, _, _) ->
                            WeightMeters(assessment, Modifier.testTag("meters-$index"))
                        }
                        WeightMeters(null, Modifier.testTag("unassessed-meters"))
                    }
                }
            }
        }
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        val nodes = nodes(root)
        cases.forEachIndexed { index, (_, confidenceLabel, impactLabel) ->
            val meter = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "meters-$index" }
            val expected = listOf(
                activity.getString(R.string.assessment_dimension, activity.getString(R.string.assessment_impact), activity.getString(impactLabel)),
                activity.getString(R.string.assessment_dimension, activity.getString(R.string.assessment_confidence), activity.getString(confidenceLabel)),
            ).joinToString(activity.getString(R.string.list_separator))
            assertEquals(listOf(expected), meter.config.getOrNull(SemanticsProperties.ContentDescription))
        }
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.TestTag) == "unassessed-meters" })
    }

    private fun checkMeterBounds(fontScale: Float, width: Int = 180) {
        val root = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    LinguizeTheme(dark = false) {
                        // The Activity may remeasure its host. Constrain the actual panel
                        // in Compose so this remains a narrow-layout test after idle().
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp)) {
                                ExplanationPanel(
                                    from = "will goes",
                                    to = "will go",
                                    description = "Replace will goes with will go",
                                    why = "Use the base form after will.",
                                    assessment = SuggestionAssessment(confidence = 3, impact = 2),
                                    assumptions = emptyList(),
                                    answers = emptyList(),
                                    accent = Linguize.colors.proof,
                                    onAccept = {},
                                    modifier = Modifier.testTag("explanation"),
                                )
                            }
                        }
                    }
                }
            }
        }
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        val nodes = nodes(root)
        val panel = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "explanation" }
        assertEquals("The panel must really be constrained to the requested width",
            width.toFloat(), panel.boundsInRoot.width, 0.01f)
        val accept = nodes.single { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == activity.getString(R.string.accept) } == true
        }
        val impact = activity.getString(R.string.assessment_impact)
        val meters = nodes.single { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(impact) } == true
        }
        val reason = nodes.single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Use the base form after will." } == true
        }
        val bounds = "panel=${panel.boundsInRoot}, prose=${reason.boundsInRoot}, Accept=${accept.boundsInRoot}, meters=${meters.boundsInRoot}"
        assertTrue("Scores must have visible bounds: $bounds",
            meters.boundsInRoot.width > 0f && meters.boundsInRoot.height > 0f)
        assertTrue("Scores must be below the button, with the panel width available: $bounds",
            meters.boundsInRoot.top >= accept.boundsInRoot.bottom)
        assertTrue("The meter area can use the space beneath Accept: $bounds",
            meters.boundsInRoot.right > accept.boundsInRoot.left)
        assertTrue("Scores stay within the panel: $bounds",
            meters.boundsInRoot.left >= panel.boundsInRoot.left && meters.boundsInRoot.right <= panel.boundsInRoot.right)
        assertTrue("Accept remains below the prose: $bounds",
            accept.boundsInRoot.top >= reason.boundsInRoot.bottom)
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
}
