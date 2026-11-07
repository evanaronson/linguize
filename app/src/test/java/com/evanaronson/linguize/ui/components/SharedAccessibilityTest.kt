package com.evanaronson.linguize.ui.components

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.ui.card.AssumptionInset
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Inspect the accessibility tree exposed by the shared controls, including their actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedAccessibilityTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private lateinit var root: ComposeView

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun populatedPasswordFieldAnnouncesItsLabelOnceAndPreservesEditingAndError() {
        var value by mutableStateOf("secret")
        show {
            LinguizeTextField(
                value = value,
                onValueChange = { value = it },
                label = "API key",
                isError = true,
                supporting = "Check the key",
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(listOf("API key"), editor.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("Check the key", editor.config.getOrNull(SemanticsProperties.Error))
        assertTrue(editor.config.contains(SemanticsProperties.Password))
        assertFalse("The visible label should not also be a separate TalkBack stop",
            nodes().any { text(it).contains("API key") })
        assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("replacement")))
        assertEquals("replacement", value)
    }

    @Test fun callerFieldSemanticsOverrideTheDefaultLabel() {
        show {
            LinguizeTextField(
                value = "Tomorrow",
                onValueChange = {},
                label = "Your answer",
                fieldModifier = Modifier.semantics { contentDescription = "Clarify: When?" },
            )
        }

        val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(listOf("Clarify: When?"), editor.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test fun fieldWithoutAVisibleLabelStillHasAnEditableAccessibilityLabel() {
        show {
            LinguizeTextField(
                value = "A sentence to check.",
                onValueChange = {},
                accessibleLabel = "Your text",
            )
        }

        val editor = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(listOf("Your text"), editor.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test fun longClarificationLinkKeepsItsReadingAndReservesRoomForTheChevronAtLargeFont() {
        val reading = "Assumes the meeting takes place tomorrow and the whole team will attend."
        var clicks = 0
        show(fontScale = 2f) {
            ClarifyLink(
                text = reading,
                onClick = { clicks++ },
                onClickLabel = "Clarify: When?",
                modifier = Modifier.width(180.dp),
            )
        }

        val link = nodes().single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Clarify: When?" }
        assertEquals(listOf(reading), text(link))
        assertNull(link.config.getOrNull(SemanticsProperties.ContentDescription))
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(link.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        val layout = layouts.single()
        assertTrue("The long reading wraps", layout.lineCount > 1)
        assertFalse("The reading does not overflow", layout.didOverflowWidth)
        assertTrue("The text leaves room for the chevron", layout.size.width < link.boundsInRoot.width)
        assertTrue(link.config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(1, clicks)
    }

    @Test fun assumptionReadsItsSubjectReadingAndWordsBeforeOfferingClarification() {
        val assumption = Assumption("When?", "Tomorrow", words = "demain", alternatives = listOf("Today"))
        show { AssumptionInset(assumption) { _, _ -> } }

        val label = activity.getString(R.string.clarify_about, assumption.about)
        val inset = nodes().single { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        assertEquals(listOf("When?", "Tomorrow", activity.getString(R.string.quoted_text, "demain")), text(inset))
        assertNull("An action label must not replace the reading",
            inset.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(inset.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
        assertTrue(nodes().any { text(it).contains(activity.getString(R.string.what_did_you_mean)) })
        assertTrue(nodes().any { text(it).contains("Today") })
    }

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        root = ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    LinguizeTheme(dark = false) { Column { content() } }
                }
            }
        }
        activity.setContentView(root)
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 1600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
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

    private fun text(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
