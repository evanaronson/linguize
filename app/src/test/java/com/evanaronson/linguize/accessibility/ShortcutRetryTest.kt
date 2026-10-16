package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.KeyguardManager
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.evanaronson.linguize.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = com.evanaronson.linguize.App::class, shadows = [ShortcutRetryTest.FocusShadow::class, ShortcutRetryTest.WindowFocusShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutRetryTest {
    private lateinit var service: LinguizeAccessibilityService
    private lateinit var focus: FocusShadow

    @Before fun setUp() {
        service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
        focus = Shadow.extract(service)
        ShadowToast.reset()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        focus.current = node()
        repeat(130) { shadowOf(focus.current).addChild(node()) }
        focus.activeRoot = focus.current
        assertEquals(focus.current, service.rootInActiveWindow)
        assertTrue(FocusedField.sameWindow(service.rootInActiveWindow!!, focus.current))
    }

    @After fun tearDown() = service.onDestroy()

    private fun tap() = ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)

    @Test fun firstContainerLookupCanReachTheNewEditorWithinTheOriginalTap() {
        tap()
        assertEquals(1, focus.queries)
        assertNull(ShadowToast.getTextOfLatestToast())
        focus.current = node(editable = true).apply { isPassword = true }
        advance()
        assertEquals(2, focus.queries)
        // The new editor's privacy metadata is checked before any text/check request.
        assertEquals(service.getString(R.string.shortcut_private_field), ShadowToast.getTextOfLatestToast())
    }

    @Test fun persistentLookupLimitStopsAfterOneRetry() {
        tap()
        advance()
        advance()
        assertEquals(2, focus.queries)
    }

    @Test fun anotherExplicitTapCancelsThePendingRetry() {
        tap()
        focus.current = node(editable = true).apply { isPassword = true }
        tap()
        advance()
        assertEquals(2, focus.queries)
    }

    @Test fun switchingWindowsCancelsTheRetryBeforeQueryingFocus() {
        tap()
        focus.activeRoot = node(app = "other.app", window = 8)
        advance()
        assertEquals(1, focus.queries)
    }

    @Test fun lockingThePhoneCancelsTheRetryBeforeQueryingFocus() {
        tap()
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        advance()
        assertEquals(1, focus.queries)
    }

    @Test fun stoppingTheServiceCancelsThePendingRetry() {
        tap()
        service.onDestroy()
        advance()
        assertEquals(1, focus.queries)
    }

    @Test fun anActuallyEmptyEditorOpensClipboardModeWithoutSchedulingARetry() {
        focus.current = node(editable = true)
        tap()
        advance()
        assertEquals(1, focus.queries)
        org.junit.Assert.assertNotNull(ReflectionHelpers.getField<Any>(service, "card"))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun aPrivateContainerDoesNotScheduleARetry() {
        focus.current.isPassword = true
        tap()
        advance()
        assertEquals(1, focus.queries)
    }

    private fun node(editable: Boolean = false, app: String = "com.openai.chatgpt", window: Int = 7): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication())).apply {
            packageName = app
            isFocused = true
            isEditable = editable
            isEnabled = true
            isVisibleToUser = true
            val info = AccessibilityWindowInfo.obtain()
            shadowOf(info).setId(window)
            shadowOf(this).setAccessibilityWindowInfo(info)
        }

    @Implements(AccessibilityService::class)
    class FocusShadow : ShadowAccessibilityService() {
        lateinit var current: AccessibilityNodeInfo
        var activeRoot: AccessibilityNodeInfo? = null
        var queries = 0
        @Implementation protected override fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRoot
        @Implementation protected fun findFocus(type: Int): AccessibilityNodeInfo {
            assertEquals(AccessibilityNodeInfo.FOCUS_INPUT, type)
            queries++
            return current
        }
    }

    /** Simulate the window query missing focus, so the bounded fallback must run.
     * Synthetic nodes are not sealed/connected to a remote accessibility process. */
    @Implements(AccessibilityNodeInfo::class)
    class WindowFocusShadow : ShadowAccessibilityNodeInfo() {
        @Implementation protected fun findFocus(type: Int): AccessibilityNodeInfo? {
            assertEquals(AccessibilityNodeInfo.FOCUS_INPUT, type)
            return null
        }
    }
}
