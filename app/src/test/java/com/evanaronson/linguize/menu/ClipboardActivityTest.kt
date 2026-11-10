package com.evanaronson.linguize.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.Understander
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.quicktile.ClipboardJob
import com.evanaronson.linguize.ui.quicktile.QuickLaunch
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** The tile and shortcut host: reads the clipboard on focus, routes it, and copies changes back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardActivityTest {
    private lateinit var app: App
    private val checks = FakeClient { ModelAnswer(QUESTION_VERDICT, com.evanaronson.linguize.QUESTION_JSON) }
    private var key: String? = "test-key"
    private val clipboard get() = app.getSystemService(ClipboardManager::class.java)

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as App
        app.settings.historyEnabled = false
        app.settings.provider = Provider.Gemini
        app.settings.setModel(Provider.Gemini, "model")
        ReflectionHelpers.setField(app, "checker\$delegate", lazyOf(
            Checker(app.settings, { key }, Prompt("instructions", "{}"), Provider.entries.associateWith { checks }),
        ))
        val reader = object : ProviderClient by checks {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String =
                """{"translation":"How are you?","notes":[]}"""
        }
        ReflectionHelpers.setField(app, "understander\$delegate", lazyOf(
            Understander(app.settings, { key }, Provider.entries.associateWith { reader }),
        ))
    }

    @Test fun theTileShowsTheCopiedTextAndLetsTheWriterChoose() {
        copy("  Com estas?\n")
        val activity = open(ClipboardJob.Choose).get()
        assertEquals(ClipboardStep.Choose(Selection.of("  Com estas?\n")), activity.step)
        assertTrue("Nothing is sent before the writer chooses", checks.checks.isEmpty())
    }

    @Test fun theClipboardIsReadOnlyOnceTheWindowHasFocus() {
        copy("Com estas?")
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java, QuickLaunch.intent(app, ClipboardJob.Check))
            .create().start().resume()
        // Android 10+ refuses clipboard reads without focus, so nothing is read before it.
        if (!controller.get().hasWindowFocus()) assertEquals(ClipboardStep.Reading, controller.get().step)
        controller.get().onWindowFocusChanged(true)
        assertEquals(ClipboardStep.Check(Selection.of("Com estas?")), controller.get().step)
    }

    @Test fun acceptedChangesReplaceTheCopiedTextWhenTheCardCloses() {
        copy(" Com estas? ")
        val activity = open(ClipboardJob.Check).get()
        val check = ViewModelProvider(activity)[CheckViewModel::class.java]
        waitFor { check.state is CardState.Done }
        assertEquals(listOf("Com estas?"), checks.checks.map { it.third.text })
        check.acceptAll(EditKind.Fix)

        activity.finish()

        assertEquals(" Com estàs? ", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(app.getString(R.string.lab_quick_tile_copied_with_changes), ShadowToast.getTextOfLatestToast())
    }

    @Test fun closingWithoutAcceptingLeavesTheClipboardAlone() {
        copy("Com estas?")
        val activity = open(ClipboardJob.Check).get()
        val check = ViewModelProvider(activity)[CheckViewModel::class.java]
        waitFor { check.state is CardState.Done }

        activity.finish()

        assertEquals("Com estas?", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(null, ShadowToast.getTextOfLatestToast())
    }

    @Test fun understandExplainsTheCopiedMessage() {
        copy("Com estas?")
        val activity = open(ClipboardJob.Understand).get()
        val understand = ViewModelProvider(activity)[UnderstandViewModel::class.java]
        waitFor { understand.state is UnderstandState.Done }
        assertEquals("How are you?", (understand.state as UnderstandState.Done).result.translation)
        assertTrue("Understanding sends no check", checks.checks.isEmpty())
    }

    @Test fun withoutAKeyTheCardSaysSoInsteadOfSending() {
        key = null
        copy("Com estas?")
        val activity = open(ClipboardJob.Check).get()
        val check = ViewModelProvider(activity)[CheckViewModel::class.java]
        waitFor { check.state is CardState.Failed }
        assertEquals(CheckFailure.Reason.NoKey, (check.state as CardState.Failed).reason)
        assertTrue(checks.checks.isEmpty())
    }

    @Test fun anEmptyClipboardOrAnImageShowsWhy() {
        clipboard.clearPrimaryClip()
        assertEquals(ClipboardStep.Message(Copied.Empty), open(ClipboardJob.Choose).get().step)
        clipboard.setPrimaryClip(ClipData.newRawUri("photo", android.net.Uri.parse("content://media/1")))
        assertEquals(ClipboardStep.Message(Copied.NotText), open(ClipboardJob.Check).get().step)
        assertTrue(checks.checks.isEmpty())
    }

    @Test fun anyOtherLaunchIsRefused() {
        val activity = Robolectric.buildActivity(ClipboardActivity::class.java, Intent(Intent.ACTION_MAIN)).create().get()
        assertTrue(activity.isFinishing)
    }

    private fun copy(text: String) = clipboard.setPrimaryClip(ClipData.newPlainText("copied", text))

    private fun open(job: ClipboardJob): ActivityController<ClipboardActivity> {
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java, QuickLaunch.intent(app, job))
            .create().start().resume().visible()
        controller.get().onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("Timed out", done())
    }
}
