package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.KeyguardManager
import android.os.Looper
import android.os.PowerManager
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.evanaronson.linguize.ui.components.LinguizeTextField
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
import java.time.Duration

/**
 * The accessibility button's card and the keyboard (docs/accessibility-spec.md, "The
 * keyboard"): the app's keyboard below closes as the card shows and stays closed (the
 * service's soft keyboard show mode is hidden) until a card field takes focus; the field then
 * opens the keyboard for the card. The writer's show mode comes back when the card hides for a
 * detour or closes. The window fits above the keyboard and inside the navigation bar.
 *
 * What only a device shows: that Android really closes the other app's keyboard for the hidden
 * show mode, and where the window and the keyboard end up on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OverlayKeyboardTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
    private val soft = service.softKeyboardController
    private val keyboard = CardKeyboard { soft }
    private val imm = activity.getSystemService(InputMethodManager::class.java)
    private val requester = FocusRequester()
    private val window = OverlayWindow(activity, keyboard) {}

    init {
        for (context in listOf(activity, activity.applicationContext, service)) {
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        }
    }

    @After fun tearDown() {
        window.remove()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun show() {
        // As on a phone: in touch mode a window gaining focus doesn't move focus into a field.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        window.show {
            MaterialTheme {
                var text by remember { mutableStateOf("") }
                LinguizeTextField(text, { text = it }, fieldModifier = Modifier.focusRequester(requester), accessibleLabel = "Field")
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Shown over an unlocked screen", androidx.lifecycle.Lifecycle.State.RESUMED, window.lifecycle.currentState)
        assertFalse("Nothing on the card has focus yet", window.keyboardUp())
    }

    @Test fun theAppsKeyboardStaysClosedUntilACardFieldIsTapped() {
        // The writer was typing in the app below: its keyboard is up.
        imm.showSoftInput(activity.window.decorView, 0)
        assertEquals(AccessibilityService.SHOW_MODE_AUTO, soft.showMode)
        show()
        assertEquals("The keyboard is kept closed while the card first shows",
            AccessibilityService.SHOW_MODE_HIDDEN, soft.showMode)

        requester.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A card field took focus: the keyboard may show again",
            AccessibilityService.SHOW_MODE_AUTO, soft.showMode)
        assertTrue("…and it opens, for the card's field", shadowOf(imm).isSoftInputVisible)

        window.remove()
        assertEquals(AccessibilityService.SHOW_MODE_AUTO, soft.showMode)
    }

    @Test fun theWritersShowModeComesBackWhenTheCardCloses() {
        show()
        assertEquals(AccessibilityService.SHOW_MODE_HIDDEN, soft.showMode)
        window.remove()
        assertEquals("Closed without a tap: the writer's mode is back",
            AccessibilityService.SHOW_MODE_AUTO, soft.showMode)
    }

    @Test fun aDetourGivesTheKeyboardBackAndReturningClosesItAgain() {
        show()
        window.hidden = true
        assertEquals("Hidden for the app or settings, the keyboard is the writer's",
            AccessibilityService.SHOW_MODE_AUTO, soft.showMode)
        window.hidden = false
        assertEquals("Back over the app, the card shows without one again",
            AccessibilityService.SHOW_MODE_HIDDEN, soft.showMode)
    }

    @Test fun aShowModeTheWriterAlreadyHadIsWhatComesBack() {
        soft.setShowMode(AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD)
        show()
        assertEquals(AccessibilityService.SHOW_MODE_HIDDEN, soft.showMode)
        window.remove()
        assertEquals(AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD, soft.showMode)
    }

    /**
     * The window lies above the keyboard and the navigation bar (an accessibility overlay is
     * layered over both), so it fits itself inside them: keys and navigation buttons stay
     * visible and get their taps. Before, it fitted the navigation bar only through Android's
     * compatibility rules, which leave the keyboard out for a window laid out in screen.
     */
    @Test fun theWindowFitsAboveTheKeyboardAndInsideTheNavigationBar() {
        val types = overlayLayoutParams().fitInsetsTypes
        assertTrue("Fits above the keyboard", types and WindowInsets.Type.ime() != 0)
        assertTrue("Never covers the navigation bar", types and WindowInsets.Type.navigationBars() != 0)
        assertFalse("The backdrop still reaches under the status bar", types and WindowInsets.Type.statusBars() != 0)
    }
}
