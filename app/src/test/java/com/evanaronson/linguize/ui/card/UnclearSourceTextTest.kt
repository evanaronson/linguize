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
import com.evanaronson.linguize.core.Settled
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
    private val root get() = activity.window.decorView

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun originalTextStaysAboveTheClarificationAndItsEditor() {
        val original = "  Jo demà això…\nperò ell?  "
        val clarification = "I meant that he will bring it tomorrow."
        activity.setContent {
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
