package com.evanaronson.linguize.accessibility

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnlockedScreenTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun screenOffHidesImmediatelyAndScreenOnWaitsForUnlock() {
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(false)
        var visible = false
        val screen = UnlockedScreen(context) { visible = it }
        screen.start()
        try {
            assertTrue(visible)
            // The broadcast can arrive before system services report the new lock state.
            broadcast(Intent.ACTION_SCREEN_OFF)
            assertFalse(visible)
            screen.refresh()
            assertFalse(visible)
            keyguard.setKeyguardLocked(true)
            broadcast(Intent.ACTION_SCREEN_ON)
            assertFalse(visible)
            // Returning from the full app must not bypass keyguard either.
            screen.refresh()
            assertFalse(visible)
            keyguard.setKeyguardLocked(false)
            broadcast(Intent.ACTION_USER_PRESENT)
            assertTrue(visible)
        } finally {
            screen.stop()
        }
    }

    @Test
    fun openingWhileLockedOrScreenOffNeverShowsTheOverlay() {
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(true)
        var visible = true
        val screen = UnlockedScreen(context) { visible = it }
        screen.start()
        try {
            assertFalse(visible)
            keyguard.setKeyguardLocked(false)
            power.setIsInteractive(false)
            screen.refresh()
            assertFalse(visible)
            power.setIsInteractive(true)
            broadcast(Intent.ACTION_SCREEN_ON)
            assertTrue(visible)
        } finally {
            screen.stop()
        }
    }

    @Test
    fun closingThePopupUnregistersScreenCallbacks() {
        var calls = 0
        val screen = UnlockedScreen(context) { calls++ }
        screen.start()
        assertEquals(1, calls)
        screen.stop()
        screen.stop()
        broadcast(Intent.ACTION_SCREEN_OFF)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals(1, calls)
    }
}
