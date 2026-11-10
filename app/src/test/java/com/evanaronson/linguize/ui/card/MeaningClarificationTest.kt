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
import com.evanaronson.linguize.R
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
                    OpenMeaning("One person is leaving.", emptyList(), emptyList(),
                        onSettle = null, onRemoveChoice = null,
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
                    OpenMeaning(
                        "A group arrived.",
                        listOf(Assumption("Who?", "One person", alternatives = listOf("A group"))),
                        listOf(Settled.meaning("I meant several friends."), Settled("Who?", "A group")),
                        onSettle = null, onRemoveChoice = null,
                    )
                }
            }
        }
        settle()
        assertTrue(hasText("Your clarification"))
        assertTrue(hasText("I meant several friends."))
        assertFalse(hasText("Your choice"))
        assertTrue(hasText("A group"))
        assertEquals("Both scopes identify the writer's answer the same way", 2, nodes().count {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Your clarification" } == true
        })
        assertFalse(hasText("Clarify"))
        assertFalse(hasText("Remove choice"))
        assertFalse(hasText("Remove clarification"))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Clarify") == true })
        assertTrue(fields().isEmpty())
    }

    @Test fun suggestionAssumptionsAndWriterAnswersKeepTheirOriginalCapitalization() {
        val readings = listOf("Maria is arriving.", "Polish is spoken.")
        activity.setContent {
            MaterialTheme {
                ExplanationPanel(
                    from = "", to = "", description = null, why = null, assessment = null,
                    assumptions = readings.mapIndexed { index, reading -> Assumption("Question $index", reading) },
                    answers = readings,
                    accent = MaterialTheme.colorScheme.primary,
                    onSettle = { _, _ -> },
                )
            }
        }
        settle()

        readings.forEach { reading ->
            assertTrue(hasText(activity.getString(R.string.assumes, reading)))
            assertTrue(hasText(activity.getString(R.string.you_said, reading)))
        }
    }

    @Test fun savedClarificationItselfOpensTheEditorAndRemovalRestoresTheInitialLink() {
        val original = "I meant several friends."
        val settled = mutableStateOf(listOf(Settled.meaning(original)))
        val answers = mutableListOf<String>()
        activity.setContent {
            MaterialTheme {
                Column {
                    MeaningSection("", settled.value,
                        onClarify = { answers += it },
                        onRemoveClarification = { settled.value = emptyList() },
                        linkText = activity.getString(R.string.say_what_you_meant))
                }
            }
        }
        settle()
        assertFalse(hasText(activity.getString(R.string.say_what_you_meant)))
        assertFalse(hasText(activity.getString(R.string.not_what_you_mean)))
        val edit = button(activity.getString(R.string.your_clarification))
        assertTrue("The answer and its label are one edit target", containsText(edit, original))
        val remove = button(activity.getString(R.string.remove_clarification))
        assertEquals("Removal shares the clarification's left edge", edit.boundsInRoot.left,
            remove.boundsInRoot.left, 1f)

        clickText(activity.getString(R.string.your_clarification))
        assertEquals(original, fieldText())
        type("An unsaved change")
        clickText("Cancel")
        assertTrue(answers.isEmpty())
        assertTrue(hasText(original))
        assertTrue(fields().isEmpty())
        clickText(activity.getString(R.string.your_clarification))
        assertEquals("Cancel keeps the saved answer", original, fieldText())
        clickText("Cancel")
        clickText(activity.getString(R.string.remove_clarification))
        assertFalse(hasText(original))
        assertTrue(hasText(activity.getString(R.string.say_what_you_meant)))
    }

    @Test fun savedAssumptionUsesTheSamePrefilledEditorAndRemovalKeepsTheMeaningClarification() {
        val original = "A group of friends"
        val meaning = Settled.meaning("I meant an invitation.")
        val settled = mutableStateOf(listOf(meaning, Settled("Who?", original)))
        val answers = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        activity.setContent {
            MaterialTheme {
                Column {
                    OpenMeaning(
                        "", listOf(Assumption("Who?", "One person")), settled.value,
                        onSettle = { about, answer ->
                            answers += about to answer
                            settled.value = settled.value.filterNot { it.answers(about) } + Settled(about, answer)
                        },
                        onRemoveChoice = { about ->
                            removed += about
                            settled.value = settled.value.filterNot { it.answers(about) }
                        },
                        onClarify = { fail("An assumption edit must keep its scope") },
                        onRemoveClarification = { fail("Removing an assumption must keep the meaning clarification") },
                    )
                }
            }
        }
        settle()
        assertTrue(hasText("Who?"))
        assertFalse(hasText("Your choice"))
        val edit = button(original)
        assertTrue("The writer's answer is part of its edit target", containsText(edit, "Your clarification"))
        val remove = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Remove clarification: Who?") == true
        }
        assertEquals("Removal shares the clarification's left edge", edit.boundsInRoot.left,
            remove.boundsInRoot.left, 1f)

        clickDescription("Clarify: Who?")
        assertEquals(original, fieldText())
        assertTrue("Opening the saved answer focuses its field",
            field().config.getOrNull(SemanticsProperties.Focused) == true)
        type("An unsaved change")
        clickText("Cancel")
        assertTrue(answers.isEmpty())
        assertTrue(fields().isEmpty())
        assertTrue(hasText(original))

        clickDescription("Clarify: Who?")
        assertEquals("Cancel preserves the saved clarification", original, fieldText())
        type("  Several colleagues  ")
        clickText("Clarify")
        assertEquals(listOf("Who?" to "Several colleagues"), answers)
        assertTrue(hasText("Several colleagues"))
        assertTrue(fields().isEmpty())
        clickDescription("Clarify: Who?")
        assertEquals("Editing again starts with the latest answer", "Several colleagues", fieldText())
        clickText("Cancel")
        clickDescription("Remove clarification: Who?")
        assertEquals(listOf("Who?"), removed)
        assertEquals(listOf(meaning), settled.value)
        assertTrue("Removing the answer restores the open model reading", hasText("One person"))
        assertTrue(hasText(meaning.answer))
    }

    @Test fun savedAssumptionRemainsEditableWhenARecheckOmitsTheOriginalQuestion() {
        val answers = mutableListOf<Pair<String, String>>()
        activity.setContent {
            MaterialTheme {
                AssumptionList(
                    assumptions = emptyList(), open = emptyList(),
                    settled = listOf(Settled("When?", "Tomorrow")),
                    onSettle = { about, answer -> answers += about to answer },
                    onRemoveChoice = null,
                )
            }
        }
        settle()
        assertTrue(hasText("When?"))
        clickDescription("Clarify: When?")
        assertEquals("Tomorrow", fieldText())
        type("Next week")
        clickText("Clarify")
        assertEquals(listOf("When?" to "Next week"), answers)
    }

    @Test fun savedAssumptionCannotBeEditedThroughAnAmbiguousLabel() {
        val assumptions = mutableStateOf(listOf(Assumption("Place", "Here"), Assumption("Place", "There")))
        val settled = mutableStateOf(listOf(Settled("Place", "At home")))
        activity.setContent {
            MaterialTheme {
                AssumptionList(assumptions.value, open = emptyList(), settled = settled.value,
                    onSettle = { _, _ -> fail("An ambiguous label must not offer an edit") },
                    onRemoveChoice = null)
            }
        }
        settle()
        assertTrue(hasText("At home"))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Clarify: Place" })

        assumptions.value = emptyList()
        settled.value = listOf(Settled("Place", "At home"), Settled("Place", "At work"))
        settle()
        assertTrue(hasText("At home"))
        assertTrue(hasText("At work"))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Clarify: Place" })
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

    /** The card's Meaning section, already open: what the drill-down page used to show. */
    @androidx.compose.runtime.Composable
    private fun OpenMeaning(
        meaning: String,
        assumptions: List<Assumption>,
        settled: List<Settled>,
        onSettle: ((String, String) -> Unit)?,
        onRemoveChoice: ((String) -> Unit)?,
        onClarify: ((String) -> Unit)? = null,
        onRemoveClarification: (() -> Unit)? = null,
    ) = MeaningLandsSection(meaningOutline(meaning, assumptions, settled, null), expanded = true, onExpandedChange = {},
        onSettle = onSettle, onRemoveChoice = onRemoveChoice, onClarify = onClarify,
        onRemoveClarification = onRemoveClarification)

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
                (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true ||
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == description)
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
