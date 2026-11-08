package com.evanaronson.linguize.ui.reply

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntent
import com.evanaronson.linguize.core.reply.ReplyIntents
import com.evanaronson.linguize.core.reply.ReplySentence
import com.evanaronson.linguize.core.reply.ReplyWord
import com.evanaronson.linguize.ui.theme.LinguizeTheme
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

/** The reply page reads as a learner tool: suggestions, then each sentence with its gloss. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyCardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val store = ViewModelStore()
    private lateinit var root: ComposeView

    private val draft = ReplyDraft(
        "Spanish",
        listOf(
            ReplySentence("¡Hola, Marta!", "Hi, Marta!", lineBreak = true),
            ReplySentence("Sí, me encantaría.", "Yes, I'd love to.", lineBreak = false),
        ),
        listOf(ReplyWord("me encantaría", "I would love to")),
    )
    private val vm = ReplyViewModel(
        suggest = { _, _ -> ReplyIntents("Spanish", listOf(ReplyIntent("Accept", "Say yes."), ReplyIntent("Decline politely", "Say no."))) },
        write = { _, _, _, _ -> draft },
    ).also { store.put("reply", it) }

    @After fun tearDown() {
        store.clear()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun suggestionsDraftAReplyShownSentenceBySentenceWithItsGloss() {
        var putBack: String? = null
        vm.start("¿Vienes a cenar?", "Are you coming to dinner?")
        show { ReplyCard(vm, onBack = {}, onPutBack = { putBack = it }) }
        waitFor { texts().contains("Accept") }
        assertTrue(texts().contains(activity.getString(R.string.lab_reply_drafter_what_to_say)))
        click("Accept")
        waitFor { vm.state is ReplyState.Drafted }
        settle()
        val shown = texts()
        for (expected in listOf("¡Hola, Marta!", "Hi, Marta!", "Sí, me encantaría.", "Yes, I'd love to.", "me encantaría", "I would love to")) {
            assertTrue("shows $expected", shown.contains(expected))
        }
        assertTrue(shown.contains(activity.getString(R.string.lab_reply_drafter_words)))
        click(activity.getString(R.string.lab_reply_drafter_put_back))
        assertEquals("¡Hola, Marta!\nSí, me encantaría.", putBack)
    }

    @Test fun withoutAFieldOnlyCopyIsOffered() {
        vm.start("¿Vienes a cenar?", "Are you coming to dinner?")
        vm.changeIntent("Say yes")
        vm.draftTyped()
        waitFor { vm.state is ReplyState.Drafted }
        show { ReplyCard(vm, onBack = {}) }
        val shown = texts()
        assertTrue(shown.contains(activity.getString(R.string.lab_reply_drafter_copy)))
        assertFalse(shown.contains(activity.getString(R.string.lab_reply_drafter_put_back)))
        vm.edit("Sí.")
        settle()
        assertTrue(texts().contains(activity.getString(R.string.lab_reply_drafter_edited_note)))
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        root = ComposeView(activity).apply {
            setContent { LinguizeTheme(dark = false) { Column { content() } } }
        }
        activity.setContentView(root)
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 4000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            settle()
            Thread.sleep(2)
        }
    }

    private fun click(text: String) {
        val node = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                (textOf(node).contains(text) || node.children.any { textOf(it).contains(text) })
        }
        node.config[SemanticsActions.OnClick].action?.invoke()
        settle()
    }

    private fun texts(): List<String> = nodes().flatMap(::textOf)

    private fun textOf(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

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
