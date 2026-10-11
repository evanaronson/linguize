package com.evanaronson.linguize.accessibility

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class OverlayWindowLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))

    @Test
    fun aHiddenOrLockedPopupCannotRunResumedOnlyUiActions() {
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(false)
        val window = OverlayWindow(context) {}
        try {
            window.show {}
            assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
            window.hidden = true
            assertEquals(Lifecycle.State.STARTED, window.lifecycle.currentState)
            keyguard.setKeyguardLocked(true)
            window.hidden = false
            assertEquals(Lifecycle.State.STARTED, window.lifecycle.currentState)
            keyguard.setKeyguardLocked(false)
            window.hidden = true
            window.hidden = false
            assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
        } finally {
            window.remove()
        }
        assertEquals(Lifecycle.State.DESTROYED, window.lifecycle.currentState)
    }

    @Test
    fun anOverlayCreatedWhileLockedNeverResumes() {
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(true)
        val window = OverlayWindow(context) {}
        try {
            window.show {}
            assertEquals(Lifecycle.State.STARTED, window.lifecycle.currentState)
        } finally {
            window.remove()
        }
    }
}
