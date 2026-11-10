package com.evanaronson.linguize.ui.card

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingOverall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** Lab, Meaning: what the text says and how it lands, as one section that opens in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeaningLandsTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = ComposeView(activity)
    private var frameTime = 0L

    private val text = "Quiero que me envies el contrato."
    private val landing = Landing(
        text = "Quiero que me envíes el contrato.",
        overall = LandingOverall.CouldLandDifferently,
        summary = "polite but cold",
        warmth = 2, directness = 4, formality = 3,
        drivers = listOf("Quiero que"),
        notes = listOf("Requests are often softened with podrías."),
    )
    private val assumptions = listOf(
        Assumption("Who sends it?", "You", alternatives = listOf("Your colleague")),
        Assumption("Which contract?", "The rental one"),
    )

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        root.setParentCompositionContext(recomposer)
        activity.setContentView(root)
    }

    @After fun tearDown() {
        root.disposeComposition()
        recomposer.cancel()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun reviewed(meaning: String = "I want you to send me the contract.", landing: Landing? = this.landing) =
        CheckResult.Reviewed(
            Revision(text, listOf(Edit(0, EditKind.Fix, 14, 20, "envies", "envíes", null))),
            listOf(EditKind.Fix), meaning = meaning, assumptions = assumptions, landing = landing,
        )

    private fun actions(onClarify: ((String) -> Unit)? = {}) = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
        onOpenSettings = null, onSettle = { _, _ -> }, onClarify = onClarify)

    @Test fun thePartsHaveOneOrderAndAPastCheckHasNothingToClarify() {
        val full = meaningOutline(" Hi. ", assumptions, listOf(Settled("Who sends it?", "Your colleague")), landing)
        assertEquals("Hi.", full.meaning)
        assertEquals(listOf(MeaningPart.Meaning, MeaningPart.Clarification, MeaningPart.Assumptions, MeaningPart.Landing),
            full.parts(canClarify = true))
        assertEquals("An answered reading is no longer open", listOf(assumptions[1]), full.open)
        assertEquals(listOf(MeaningPart.Meaning, MeaningPart.Assumptions, MeaningPart.Landing), full.parts(canClarify = false))

        val native = meaningOutline("", emptyList(), emptyList(), null)
        assertEquals("Already in your language, a live check can still be clarified",
            listOf(MeaningPart.Clarification), native.parts(canClarify = true))
        assertTrue("Nothing to show on a past check", native.parts(canClarify = false).isEmpty())
        val clarified = meaningOutline("", emptyList(), listOf(Settled.meaning("I meant it")), null)
        assertEquals(listOf(MeaningPart.Clarification), clarified.parts(canClarify = false))
    }

    @Test fun collapsedItIsTheMeaningTheReadingAndTheOpenAssumptionsOnOneHeader() {
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed()), actions()) } }
        settle()
        val header = header()
        val lines = header.config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf(
            activity.getString(R.string.meaning),
            "I want you to send me the contract.",
            landingLine(),
            activity.resources.getQuantityString(R.plurals.lab_meaning_lands_assumptions, 2, 2),
        ), lines)
        assertTrue("The header is a heading for screen readers", header.config.contains(SemanticsProperties.Heading))
        assertEquals(activity.getString(R.string.lab_expand_pattern_collapsed), header.config[SemanticsProperties.StateDescription])
        assertEquals(activity.getString(R.string.lab_meaning_lands_show), header.config[SemanticsActions.OnClick].label)
        assertTrue(header.config.contains(SemanticsActions.Expand))
        assertFalse(hasText(activity.getString(R.string.not_what_you_mean)))
        assertFalse(hasText(activity.getString(R.string.lab_tone_check_title)))
        assertFalse("No separate Meaning page entry", hasText(activity.getString(R.string.meaning_subtitle)))
    }

    @Test fun expandedItReadsMeaningThenClarifyThenAssumptionsThenHowItLands() {
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed()), actions()) } }
        settle()
        click(header())
        val header = header()
        assertEquals("The summary gives way to the full content", listOf(activity.getString(R.string.meaning)),
            header.config[SemanticsProperties.Text].map { it.text })
        assertEquals(activity.getString(R.string.lab_expand_pattern_expanded), header.config[SemanticsProperties.StateDescription])
        assertEquals(activity.getString(R.string.lab_meaning_lands_hide), header.config[SemanticsActions.OnClick].label)

        val order = listOf(
            activity.getString(R.string.meaning),
            "I want you to send me the contract.",
            activity.getString(R.string.not_what_you_mean),
            activity.getString(R.string.assumptions),
            "Who sends it?",
            activity.getString(R.string.lab_tone_check_title),
            landingLine(),
            "Requests are often softened with podrías.",
        )
        val all = nodes()
        val positions = order.map { wanted ->
            all.indexOfFirst { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == wanted } == true }
        }
        assertTrue("$order at $positions", positions.all { it >= 0 })
        assertEquals("Screen readers meet the parts in this order", positions.sorted(), positions)
        assertTrue(all.any { it.config.contains(SemanticsProperties.Heading) &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == activity.getString(R.string.lab_tone_check_title) } == true })

        click(header())
        assertFalse(hasText(activity.getString(R.string.not_what_you_mean)))
        assertTrue(hasText(landingLine()))
    }

    @Test fun clarifyingFromInsideRechecksAndReopensOnTheNewReading() {
        val state = mutableStateOf<CardState>(CardState.Done(reviewed(), sourceText = text))
        val answers = mutableListOf<String>()
        root.setContent {
            MaterialTheme {
                ResultCard(state.value, actions { answer ->
                    answers += answer
                    state.value = CardState.Loading(text)
                })
            }
        }
        settle()
        click(header())
        click(nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == activity.getString(R.string.clarify_meaning) })
        val field = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("I meant my landlord should send it")))
        settle()
        click(nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == activity.getString(R.string.clarify) } == true })
        assertEquals(listOf("I meant my landlord should send it"), answers)
        assertFalse("Checking again", hasText(activity.getString(R.string.meaning)))

        val warmer = landing.copy(overall = LandingOverall.LandsWell, summary = "polite")
        state.value = CardState.Done(reviewed(meaning = "I want my landlord to send the contract.", landing = warmer),
            settled = listOf(Settled.meaning("I meant my landlord should send it")), sourceText = text)
        settle()
        assertEquals("Still open on the new answer", activity.getString(R.string.lab_expand_pattern_expanded),
            header().config[SemanticsProperties.StateDescription])
        assertTrue(hasText("I want my landlord to send the contract."))
        assertTrue(hasText("I meant my landlord should send it"))
        assertTrue("How it lands follows the new reading", hasText(landingLine(warmer)))

        state.value = CardState.Done(reviewed(), sourceText = "Another message")
        settle()
        assertEquals("A different text starts closed", activity.getString(R.string.lab_expand_pattern_collapsed),
            header().config[SemanticsProperties.StateDescription])
    }

    @Test fun aTextAlreadyInYourLanguageStillOpensToClarify() {
        root.setContent {
            MaterialTheme {
                ResultCard(CardState.Done(CheckResult.Reviewed(Revision("Hello", emptyList()), listOf(EditKind.Fix))), actions())
            }
        }
        settle()
        assertEquals(listOf(activity.getString(R.string.meaning), activity.getString(R.string.meaning_subtitle)),
            header().config[SemanticsProperties.Text].map { it.text })
        click(header())
        assertTrue(hasText(activity.getString(R.string.not_what_you_mean)))
    }

    @Test fun aReadOnlyCardWithNothingToShowHasNoSection() {
        root.setContent {
            MaterialTheme {
                ResultCard(CardState.Done(CheckResult.Reviewed(Revision("Hello", emptyList()), listOf(EditKind.Fix))),
                    actions(onClarify = null))
            }
        }
        settle()
        assertFalse(hasText(activity.getString(R.string.meaning)))
    }

    private fun landingLine(landing: Landing = this.landing) = activity.getString(R.string.lab_tone_check_row_subtitle,
        activity.getString(when (landing.overall) {
            LandingOverall.LandsWell -> R.string.lab_tone_check_lands_well
            LandingOverall.CouldLandDifferently -> R.string.lab_tone_check_could_land_differently
            LandingOverall.CouldLandBadly -> R.string.lab_tone_check_could_land_badly
        }), landing.summary)

    private fun header() = nodes().single {
        it.config.getOrNull(SemanticsActions.OnClick) != null &&
            it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == activity.getString(R.string.meaning)
    }

    private fun click(node: SemanticsNode) {
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun settle() {
        repeat(40) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            frameTime += 16_666_667L
            frames.sendFrame(frameTime)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            ownerView(root)?.forceLayout()
            root.forceLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 1920)
            shadowOf(Looper.getMainLooper()).idle()
        }
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
