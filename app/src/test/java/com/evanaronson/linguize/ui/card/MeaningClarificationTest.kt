package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
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

/** Exercise the live and historical controls through their accessibility actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeaningClarificationTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test fun nativeTextWithNoMeaningOrAssumptionsStillOffersClarification() {
        val answers = mutableListOf<String>()
        activity.setContent {
            MaterialTheme {
                ResultCard(
                    CardState.Done(CheckResult.Reviewed(Revision("Hola", emptyList()), listOf(EditKind.Fix))),
                    actions({ answers += it }),
                )
            }
        }
        settle()
        assertTrue(hasText("Meaning"))
        assertTrue(fields().isEmpty())
        clickText("Meaning")
        clickDescription("Clarify meaning")
        assertTrue("Opening the editor focuses its field",
            field().config.getOrNull(SemanticsProperties.Focused) == true)
        type("  I was greeting everyone  ")
        clickText("Clarify")
        assertEquals(listOf("I was greeting everyone"), answers)
    }

    @Test fun meaningOnlyEditorRejectsBlankAndOverlongAnswersAndCancelDiscardsTheDraft() {
        val answers = mutableListOf<String>()
        activity.setContent {
            MaterialTheme {
                Column {
                    UnderstandingPage("One person is leaving.", emptyList(), emptyList(),
                        onSettle = null, onRemoveChoice = null, onBack = {},
                        onClarify = { answers += it })
                }
            }
        }
        settle()
        clickDescription("Clarify meaning")
        assertTrue(disabled("Clarify"))
        type(" \n ")
        assertTrue(disabled("Clarify"))
        assertFalse(hasError())

        val overlong = "x".repeat(Checker.MAX_CHARS + 1)
        type(overlong)
        assertEquals("Pasted input is retained without truncation", overlong, fieldText())
        assertTrue(disabled("Clarify"))
        assertTrue(hasError())

        type("  Everyone is leaving.  ")
        assertFalse(disabled("Clarify"))
        assertFalse(hasError())
        clickText("Cancel")
        assertTrue(answers.isEmpty())
        assertTrue(fields().isEmpty())

        clickDescription("Clarify meaning")
        assertEquals("", fieldText())
        type("  Everyone is leaving.  ")
        clickText("Clarify")
        assertEquals(listOf("Everyone is leaving."), answers)
    }

    @Test fun assumptionKeepsItsSuggestedChoicesAndAcceptsSomethingElse() {
        val answers = mutableListOf<Pair<String, String>>()
        activity.setContent {
            MaterialTheme {
                AssumptionInset(Assumption("Who?", "I", alternatives = listOf("You"))) { about, answer ->
                    answers += about to answer
                }
            }
        }
        settle()
        clickDescription("Clarify: Who?")
        assertTrue(hasText("I"))
        assertTrue(hasText("You"))
        assertTrue(hasText("Something else"))
        assertTrue("Nothing is picked yet", disabled("Clarify"))
        clickText("You")
        assertTrue("Picking a reading doesn't check again by itself", answers.isEmpty())
        clickText("Clarify")
        assertEquals(listOf("Who?" to "You"), answers)

        clickDescription("Clarify: Who?")
        clickText("Something else")
        assertTrue("Something else needs words first", disabled("Clarify"))
        type("  A group of friends  ")
        clickText("Clarify")
        assertEquals(listOf("Who?" to "You", "Who?" to "A group of friends"), answers)
    }

    @Test fun assumptionWithoutAlternativesCanBeConfirmedOrAnsweredInTheWritersOwnWords() {
        val answers = mutableListOf<Pair<String, String>>()
        activity.setContent {
            MaterialTheme {
                AssumptionInset(Assumption("When?", "Tomorrow")) { about, answer ->
                    answers += about to answer
                }
            }
        }
        settle()
        clickDescription("Clarify: When?")
        assertTrue("The current reading can be confirmed", hasText("Tomorrow"))
        assertTrue(fields().isEmpty())
        clickText("Something else")
        assertEquals(1, fields().size)
        type("  Later this week  ")
        clickText("Clarify")
        assertEquals(listOf("When?" to "Later this week"), answers)
    }

    @Test fun historicalMeaningAndAnswersRemainReadableWithoutClarificationControls() {
        activity.setContent {
            MaterialTheme {
                Column {
                    UnderstandingPage(
                        "A group arrived.",
                        listOf(Assumption("Who?", "One person", alternatives = listOf("A group"))),
                        listOf(Settled.meaning("I meant several friends."), Settled("Who?", "A group")),
                        onSettle = null, onRemoveChoice = null, onBack = {},
                    )
                }
            }
        }
        settle()
        assertTrue(hasText("Your clarification"))
        assertTrue(hasText("I meant several friends."))
        assertTrue(hasText("Your choice"))
        assertTrue(hasText("A group"))
        assertFalse(hasText("Clarify"))
        assertFalse(hasText("Remove choice"))
        assertFalse(hasText("Remove clarification"))
                assertTrue(fields().isEmpty())
    }

    @Test fun unclearResultCanReviseItsSavedMeaningButWrongLanguageDoesNotOfferTheFlow() {
        val answers = mutableListOf<String>()
        val state = mutableStateOf<CardState>(CardState.Done(
            CheckResult.Unclear, settled = listOf(Settled.meaning("An earlier explanation."))))
        activity.setContent {
            MaterialTheme { ResultCard(state.value, actions({ answers += it })) }
        }
        settle()
        assertTrue(hasText("An earlier explanation."))
        clickDescription("Clarify meaning")
        assertEquals("An earlier explanation.", fieldText())
        type("  I meant a different person.  ")
        clickText("Clarify")
        assertEquals(listOf("I meant a different person."), answers)

        state.value = CardState.Done(CheckResult.WrongLanguage("Catalan", "English"))
        settle()
        assertFalse(hasText("Clarify"))
        assertTrue(fields().isEmpty())
    }

    private fun actions(onClarify: (String) -> Unit) = CardActions(
        onCopy = { _, _ -> },
        review = null,
        onRetry = {},
        onOpenSettings = null,
        onSettle = { _, _ -> fail("A global clarification must not answer an assumption") },
        onClarify = onClarify,
    )

    private fun clickDescription(description: String) {
        val node = nodes().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun clickText(text: String) {
        assertFalse("The control must be enabled", disabled(text))
        assertTrue(button(text).config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun button(text: String) = nodes().first { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && containsText(node, text)
    }

    private fun disabled(text: String) = button(text).config.contains(SemanticsProperties.Disabled)

    private fun type(text: String) {
        assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
        settle()
    }

    private fun hasError() = nodes().any { it.config.getOrNull(SemanticsProperties.Error) != null }
    private fun fields() = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
    private fun field() = fields().single()
    private fun fieldText() = field().config[SemanticsProperties.EditableText].text
    private fun hasText(text: String) = nodes().any { containsText(it, text) }
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
