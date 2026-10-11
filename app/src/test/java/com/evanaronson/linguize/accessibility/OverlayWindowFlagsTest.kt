package com.evanaronson.linguize.accessibility

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowFlagsTest {
    @Test
    fun shownTakesKeysButNotTheKeyboard() {
        val flags = windowFlags(hidden = false)
        assertEquals(0, flags and LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(flags and LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
    }

    @Test
    fun hiddenPassesEverythingThroughAndIsNoKeyboardTarget() {
        val flags = windowFlags(hidden = true)
        assertTrue(flags and LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        // With both of these set the window would still count as an input-method target.
        assertEquals(0, flags and LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }
}
