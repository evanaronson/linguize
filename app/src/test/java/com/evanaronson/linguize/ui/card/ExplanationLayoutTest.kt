package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.After
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

    private fun checkMeterBounds(fontScale: Float) {
        val root = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    LinguizeTheme(dark = false) {
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
                        )
                    }
                }
            }
        }
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 180, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        val nodes = nodes(root)
        val accept = nodes.single { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == activity.getString(R.string.accept) } == true
        }
        val impact = activity.getString(R.string.assessment_impact)
        val meters = nodes.single { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(impact) } == true
        }
        assertTrue("Scores must be below the button, with the panel width available",
            meters.boundsInRoot.top >= accept.boundsInRoot.bottom)
        assertTrue("The meter area can use the space beneath Accept",
            meters.boundsInRoot.right > accept.boundsInRoot.left)
        assertTrue(meters.boundsInRoot.width > 0f && meters.boundsInRoot.height > 0f)
        assertTrue(meters.boundsInRoot.left >= 0f && meters.boundsInRoot.right <= 180f)
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
