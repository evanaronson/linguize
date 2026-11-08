package com.evanaronson.linguize.ui.hearit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.ResultCard
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HearItCardTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup().visible().windowFocusChanged(true)
    private val activity = controller.get()
    private val root get() = activity.window.decorView

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun reviewed(): CheckResult.Reviewed {
        val fix = Edit(0, EditKind.Fix, 8, 18, "vaig poder", "podré", "Future")
        return CheckResult.Reviewed(Revision("Demà no vaig poder venir", listOf(fix)), listOf(EditKind.Fix),
            meaning = "Tomorrow I won't be able to come.")
    }

    private val actions = CardActions(
        onCopy = { _, _ -> }, review = null, onRetry = {}, onOpenSettings = null, onSettle = { _, _ -> },
    )

    @Test fun reviewedCardOffersHearItAndRemembersSlow() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences(PrefsSpeedStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        activity.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed()), actions) } }
        settle()
        assertTrue(hasDescription("Hear your message"))

        click("Play slowly")
        assertTrue(app.getSharedPreferences(PrefsSpeedStore.PREFS, Context.MODE_PRIVATE).getBoolean("slow", false))

        click("Hear your message")
        // Robolectric's engine either starts (Stop) or has no voice: both are handled states.
        assertTrue(hasDescription("Stop playing your message") || hasText("No voice for Catalan (Spain)") ||
            hasText("No speech engine"))
    }

    @Test fun meaningPageOffersHearTheMeaning() {
        activity.setContent { MaterialTheme { ResultCard(CardState.Done(reviewed()), actions) } }
        settle()
        clickText("Meaning")
        assertTrue(hasDescription("Hear the meaning"))
        assertFalse("The message control belongs to the suggestions page", hasDescription("Hear your message"))
    }

    @Test fun cardLeavingTheScreenReleasesHearIt() {
        val shown = mutableStateOf(true)
        activity.setContent {
            MaterialTheme { if (shown.value) ResultCard(CardState.Done(reviewed()), actions) }
        }
        settle()
        click("Hear your message")
        shown.value = false
        settle()
        assertFalse(hasDescription("Hear your message"))
        assertFalse(hasDescription("Stop playing your message"))
    }

    @Test fun dictationAsksForOneFreeFormResultInTheLocale() {
        val intent = dictationIntent(Locale.forLanguageTag("es-ES"), "Say your message in Spanish")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("es-ES", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertEquals("Say your message in Spanish", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
    }

    @Test fun dictationResultTakesTheBestGuessOnly() {
        val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" ", " ya voy ", "ya boy"))
        assertEquals("ya voy", dictationResult(Activity.RESULT_OK, data))
        assertNull(dictationResult(Activity.RESULT_CANCELED, data))
        assertNull(dictationResult(Activity.RESULT_OK, null))
        assertNull(dictationResult(Activity.RESULT_OK, Intent()))
    }

    @Test fun voiceSettingsFallBackWhenTheScreenIsMissing() {
        // No activity handles either intent here: the attempt must not crash.
        openVoiceSettings(activity, SpeechProblem.NoEngine)
        openVoiceSettings(activity, SpeechProblem.MissingVoice(Locale.forLanguageTag("ca-ES"), downloadable = true))
        val started = shadowOf(activity).nextStartedActivity
        if (started != null) assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun hasDescription(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true
    }

    private fun click(description: String) {
        val node = nodes().first {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private fun clickText(text: String) {
        fun contains(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || node.children.any(::contains)
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && contains(it) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
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
