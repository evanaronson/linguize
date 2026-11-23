package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.testing.OwnComposition
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnclearSourceTextTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val compose = OwnComposition(activity, height = 1920, exactWidth = false)

    @After fun tearDown() {
        compose.dispose()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun originalTextStaysAboveTheClarificationAndItsEditor() {
        val original = "  Jo demà això…\nperò ell?  "
        val clarification = "I meant that he will bring it tomorrow."
        compose.show(SETTLE_FRAMES) {
            MaterialTheme {
                ResultCard(
                    CardState.Done(CheckResult.Unclear,
                        settled = listOf(Settled.meaning(clarification)), sourceText = original),
                    CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
                        onOpenSettings = null, onSettle = { _, _ -> }, onClarify = {}),
                )
            }
        }
        settle()

        val source = textNode(original)
        val label = textNode(activity.getString(R.string.original_text))
        val link = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.clarify_meaning)
        }
        assertTrue(label.boundsInRoot.bottom <= source.boundsInRoot.top)
        assertTrue("The checked text appears above the clarification control",
            source.boundsInRoot.bottom <= link.boundsInRoot.top)
        assertTrue(source.boundsInRoot.bottom <= textNode(clarification).boundsInRoot.top)
        assertFalse("The source is read-only", source.config.contains(SemanticsActions.SetText))

        assertTrue(link.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
        val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(clarification, editor.config[SemanticsProperties.EditableText].text)
        assertTrue("The original stays visible above the open clarification editor",
            textNode(original).boundsInRoot.bottom <= editor.boundsInRoot.top)
    }

    private fun textNode(text: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun settle() = compose.settle(SETTLE_FRAMES)

    private fun nodes(): List<SemanticsNode> = compose.nodes()

    private companion object {
        /** As long as this test waited before it had its own clock: 800 ms. */
        const val SETTLE_FRAMES = 50
    }
}
