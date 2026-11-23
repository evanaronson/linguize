package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.testing.OwnComposition
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
import java.io.File
import java.time.Duration

/**
 * Lab, Replying to on the card. Each state is checked in a fresh composition; taps are checked
 * through what they did (the state or the callback), not through a recomposition after them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyingToSectionTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val compose = OwnComposition(activity, height = 1920, exactWidth = false)
    private val checked = mutableListOf<String>()
    private var screen: List<String> = emptyList()
    private var clipboard: String? = null
    private var reads = 0

    @After fun tearDown() {
        compose.dispose()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun reply(canPick: Boolean = true) = ReplyContextActions(
        pick = if (canPick) ({ reads++; screen }) else null,
        paste = { clipboard },
        checkAgain = { checked += it },
    )

    private fun actions(reply: ReplyContextActions?) = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
        onOpenSettings = null, onSettle = { _, _ -> }, onClarify = {}, replyContext = reply)

    @Test fun theSectionSitsBelowClarifyOnAnUnclearCheckFromAnotherApp() {
        show { ResultCard(CardState.Done(CheckResult.Unclear, sourceText = "a les 5"), actions(reply())) }
        val heading = textNode(activity.getString(R.string.replying_to_what))
        val clarify = nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.clarify_meaning) }
        assertTrue("Below Clarify what you mean", clarify.boundsInRoot.bottom <= heading.boundsInRoot.top)
        assertTrue(hasText(activity.getString(R.string.replying_to_pick)))
        assertTrue(hasText(activity.getString(R.string.replying_to_paste)))
        assertEquals("Nothing is read before a tap", 0, reads)
    }

    @Test fun noSectionWithoutReplyActionsAsInChatAndNotes() {
        show { ResultCard(CardState.Done(CheckResult.Unclear, sourceText = "a les 5"), actions(null)) }
        assertTrue(hasText(activity.getString(R.string.ai_could_not_understand)))
        assertFalse(hasText(activity.getString(R.string.replying_to_what)))
    }

    @Test fun noSectionOnAReviewedCheck() {
        show {
            ResultCard(CardState.Done(CheckResult.Reviewed(Revision("Hola", emptyList()), listOf(EditKind.Fix))),
                actions(reply()))
        }
        assertFalse(hasText(activity.getString(R.string.replying_to_what)))
    }

    @Test fun onlyPasteWhenTheScreenCantBeRead() {
        show { ReplyingToSection(reply(canPick = false)) }
        assertTrue(hasText(activity.getString(R.string.replying_to_what)))
        assertFalse(hasText(activity.getString(R.string.replying_to_pick)))
        assertTrue(hasText(activity.getString(R.string.replying_to_paste)))
    }

    @Test fun pickReadsTheScreenAtTheTapAndOffersTheLatestMessages() {
        screen = listOf("Hola!", "Com va?", "  ", "Vens dissabte?", "A quina hora?", "On quedem?")
        val state = ReplyPickState()
        show { ReplyingToSection(reply(), state) }
        click(activity.getString(R.string.replying_to_pick))
        assertEquals(1, reads)
        assertEquals(ReplyStep.Picking(listOf("Com va?", "Vens dissabte?", "A quina hora?", "On quedem?")), state.step)

        show { ReplyingToSection(reply(), ReplyPickState(state.step)) }
        assertTrue(hasText(activity.getString(R.string.replying_to_tap_their_message)))
        val choose = activity.getString(R.string.replying_to_choose_message)
        val rows = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick)?.label == choose }
        assertEquals(4, rows.size)
        assertFalse(hasText("Hola!"))
        assertTrue(rows.zipWithNext().all { (a, b) -> a.boundsInRoot.bottom <= b.boundsInRoot.top })
    }

    @Test fun tappingARowChoosesIt() {
        val state = ReplyPickState(ReplyStep.Picking(listOf("Vens dissabte?", "A quina hora?")))
        show { ReplyingToSection(reply(), state) }
        val row = nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "A quina hora?" } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(ReplyStep.Chosen("A quina hora?"), state.step)
        assertTrue("Choosing sends nothing", checked.isEmpty())
    }

    @Test fun anEmptyScreenSaysToCopyAndPaste() {
        val state = ReplyPickState()
        state.pick(reply())
        assertEquals(ReplyStep.NoneFound, state.step)
        show { ReplyingToSection(reply(), state) }
        assertTrue(hasText(activity.getString(R.string.replying_to_no_messages)))
        assertEquals("No messages found. Copy it, then tap Paste.", activity.getString(R.string.replying_to_no_messages))
    }

    @Test fun anEmptyClipboardSaysToCopyFirst() {
        val state = ReplyPickState()
        show { ReplyingToSection(reply(), state) }
        clipboard = "   "
        click(activity.getString(R.string.replying_to_paste))
        assertEquals(ReplyStep.EmptyClipboard, state.step)
        show { ReplyingToSection(reply(), ReplyPickState(state.step)) }
        assertTrue(hasText(activity.getString(R.string.replying_to_empty_clipboard)))
        assertEquals("Copy their message first.", activity.getString(R.string.replying_to_empty_clipboard))
    }

    @Test fun pasteShowsTheMessageWithRemoveAndCheckAgain() {
        val state = ReplyPickState()
        clipboard = "  Quedem a les 5?  "
        state.paste(reply())
        assertEquals(ReplyStep.Chosen("Quedem a les 5?"), state.step)
        show { ReplyingToSection(reply(), state) }
        assertTrue(hasText(activity.getString(R.string.replying_to)))
        assertTrue(hasText("“Quedem a les 5?”"))
        val remove = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Remove") == true }
        assertTrue(remove.config.contains(SemanticsActions.OnClick))
        assertFalse("The × itself is not read out", hasText("×"))

        click(activity.getString(R.string.check_again))
        assertEquals(listOf("Quedem a les 5?"), checked)

        assertTrue(remove.config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(ReplyStep.Choose, state.step)
    }

    @Test fun aLongMessageKeepsItsEnd() {
        val long = "x".repeat(ReplyPrompt.MAX_CHARS) + "end?"
        assertEquals(ReplyPrompt.MAX_CHARS, ReplyContextActions.message(long)!!.length)
        assertTrue(ReplyContextActions.message(long)!!.endsWith("end?"))
        assertNull(ReplyContextActions.message("  "))
    }

    /** The section is offered by the hosts checking text from other apps, never Chat or Notes. */
    @Test fun onlyOtherAppHostsOfferIt() {
        val main = File("src/main/java/com/evanaronson/linguize")
        fun offers(path: String) = File(main, path).readText().contains("replyContext = ReplyContextActions.of(")
        listOf("menu/CheckActivity.kt", "menu/ShareActivity.kt", "menu/ClipboardActivity.kt",
            "accessibility/LinguizeAccessibilityService.kt").forEach { assertTrue(it, offers(it)) }
        val offering = main.walkTopDown().filter { it.extension == "kt" && it.readText().contains("ReplyContextActions.of(") }
            .map { it.relativeTo(main).path }.toSet()
        assertTrue(offering.none { it.startsWith("ui/chat") || it.startsWith("ui/notes") })
        // Only the accessibility button's card can read the app behind it.
        assertTrue(File(main, "accessibility/LinguizeAccessibilityService.kt").readText().contains("pick = field?.let"))
    }

    private fun show(content: @Composable () -> Unit) {
        compose.show(SETTLE_FRAMES) { MaterialTheme { content() } }
        settle()
    }

    private fun click(label: String) {
        val node = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
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
