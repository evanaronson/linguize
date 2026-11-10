package com.evanaronson.linguize.ui.tonecheck

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingOverall
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.ResultCard
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

/** Lab, How it lands on the rendered check card: part of the Meaning section, which opens in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandingDetailsTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = ComposeView(activity)
    private var frameTime = 0L

    private val landing = Landing(
        text = "Quiero que me envíes el contrato.",
        overall = LandingOverall.LandsWell,
        summary = "friendly, a bit direct",
        warmth = 3, directness = 4, formality = 2,
        drivers = listOf("Quiero que"),
        notes = listOf("Requests are often softened with podrías."),
    )
    private val actions = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> })

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

    private fun reviewed(landing: Landing?) = CheckResult.Reviewed(
        Revision("Quiero que me envies el contrato.", listOf(Edit(0, EditKind.Fix, 14, 20, "envies", "envíes", null))),
        listOf(EditKind.Fix),
        meaning = "I want you to send me the contract.",
        landing = landing,
    )

    @Test fun theReadingIsMeaningsSecondLineAndItsDetailsOpenInPlace() {
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed(landing)), actions) } }
        settle()
        val row = activity.getString(R.string.lab_tone_check_row_subtitle, activity.getString(R.string.lab_tone_check_lands_well), "friendly, a bit direct")
        assertTrue(hasText(activity.getString(R.string.meaning)))
        assertTrue(hasText(row))
        assertFalse("No separate How it lands row", hasText(activity.getString(R.string.lab_tone_check_title)))
        assertFalse(hasText("Requests are often softened with podrías."))
        val directness = activity.getString(R.string.lab_tone_check_scale_description, activity.getString(R.string.lab_tone_check_directness),
            4, Landing.SCALE_MAX, activity.getString(R.string.lab_tone_check_indirect), activity.getString(R.string.lab_tone_check_blunt))
        assertFalse(hasDescription(directness))

        clickText(R.string.meaning)
        // Still the card's suggestions: the details open below the header, not as a page.
        assertTrue(hasText(activity.getString(R.string.lab_tone_check_title)))
        assertTrue(hasText(row))
        assertTrue(hasText(landing.text))
        assertTrue(hasDescription(directness))
        assertTrue(hasText("Requests are often softened with podrías."))
        assertTrue(hasDescription(activity.getString(R.string.lab_tone_check_drivers_description, "“Quiero que”")))

        clickText(R.string.meaning)
        assertFalse(hasText("Requests are often softened with podrías."))
    }

    @Test fun withoutALandingMeaningHasNoReading() {
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed(null)), actions) } }
        settle()
        assertTrue(hasText(activity.getString(R.string.meaning)))
        clickText(R.string.meaning)
        assertFalse(hasText(activity.getString(R.string.lab_tone_check_title)))
        assertFalse(hasText(activity.getString(R.string.lab_tone_check_caveat)))
    }

    @Test fun theDriversAreHighlightedWhereverTheyOccur() {
        val text = "Quiero que vengas. Quiero que sepas."
        assertEquals(listOf(0 until 10, 19 until 29, 30 until 35), driverRanges(text, listOf("Quiero que", "sepas", "")))
        val marked = marked(text, listOf("sepas"), Color.Yellow)
        assertEquals(text, marked.text)
        assertEquals(30, marked.spanStyles.single().start)
    }

    private fun clickText(id: Int) {
        val text = activity.getString(id)
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && containsText(it, text) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun hasText(text: String) = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    private fun hasDescription(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    private fun containsText(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.children.any { containsText(it, text) }

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
