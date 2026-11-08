package com.evanaronson.linguize.ui.tonecheck

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingDriver
import com.evanaronson.linguize.core.lab.LandingRisk
import com.evanaronson.linguize.core.lab.LandingVersion
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.ResultCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** "How it lands" through the rendered check card: open, read, use a version, go back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandsCardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val frames = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = ComposeView(activity)
    private var frameTime = 0L

    private val version = LandingVersion("More polite", "¿Me podrías enviar el contrato mañana?", "A question, not an order.")
    private val landing = Landing(
        variety = "Spanish (Spain)", summary = "Reads as an order.", risk = LandingRisk.WorthALook,
        warmth = 2, directness = 5, formality = 3,
        drivers = listOf(LandingDriver("Quiero que", "Sounds like a demand")),
        notes = listOf("Requests are usually softened."), versions = listOf(version),
    )
    private val asked = mutableListOf<String>()
    private val chosen = mutableStateOf<String?>(null)
    private var applied = 0
    private var failWith: CheckFailure? = null

    private val lands = LandsActions(
        analyze = { text -> asked += text; failWith?.let { failWith = null; throw it }; landing },
        onCopy = {},
        onUse = { chosen.value = it },
        chosen = { chosen.value },
        onApply = { applied++ },
        onOpenSettings = null,
    )
    private val actions = CardActions(onCopy = { _, _ -> }, review = null, onRetry = {},
        onOpenSettings = null, onSettle = { _, _ -> }, howItLands = lands)

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

    private fun reviewed(accepted: Boolean): CheckResult.Reviewed {
        val revision = Revision("Quiero que me envies el contrato mañana.",
            listOf(Edit(0, EditKind.Fix, 14, 20, "envies", "envíes", null)))
        return CheckResult.Reviewed(if (accepted) revision.accept(0) else revision, listOf(EditKind.Fix))
    }

    @Test fun readsTheAcceptedTextAndAVersionCanBeUsedAndApplied() {
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed(accepted = true)), actions) } }
        settle()
        clickText(R.string.lab_tone_check_title)
        assertEquals(listOf("Quiero que me envíes el contrato mañana."), asked)
        assertTrue(hasText(activity.getString(R.string.lab_tone_check_risk_worth_a_look)))
        assertTrue(hasText("Reads as an order."))
        assertTrue(hasText(activity.getString(R.string.lab_tone_check_quote, "Quiero que")))
        assertTrue(hasDescription(activity.getString(R.string.lab_tone_check_scale_description,
            activity.getString(R.string.lab_tone_check_directness), 5, 5,
            activity.getString(R.string.lab_tone_check_indirect), activity.getString(R.string.lab_tone_check_blunt))))

        clickText(R.string.lab_tone_check_use)
        assertEquals(version.text, chosen.value)
        assertTrue(hasText(activity.getString(R.string.lab_tone_check_chosen)))
        clickText(R.string.apply)
        assertEquals(1, applied)
        clickText(R.string.undo)
        assertNull(chosen.value)
    }

    @Test fun systemBackReturnsToTheSuggestionsAndKeepsTheReading() {
        var hostBack = 0
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { hostBack++ }
        })
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed(accepted = false)), actions) } }
        settle()
        clickText(R.string.lab_tone_check_title)
        chosen.value = version.text
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(0, hostBack)
        assertTrue("The entry says a version is in use", hasText(activity.getString(R.string.lab_tone_check_entry_subtitle_chosen)))
        clickText(R.string.lab_tone_check_title)
        assertEquals("Reopening shows the same reading", 1, asked.size)
    }

    @Test fun aFailedReadingCanBeRetried() {
        failWith = CheckFailure(CheckFailure.Reason.Offline)
        root.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed(accepted = false)), actions) } }
        settle()
        clickText(R.string.lab_tone_check_title)
        clickText(R.string.retry)
        assertTrue(hasText("Reads as an order."))
        assertEquals(2, asked.size)
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
