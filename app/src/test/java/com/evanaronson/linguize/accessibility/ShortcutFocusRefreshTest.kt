package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PowerManager
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers

/** Refresh can succeed while the direct editor loses input focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class, shadows = [
    ShortcutFocusRefreshTest.FocusShadow::class,
    ShortcutFocusRefreshTest.RefreshShadow::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutFocusRefreshTest {
    private lateinit var service: LinguizeAccessibilityService
    private lateinit var field: AccessibilityNodeInfo
    private lateinit var node: RefreshShadow
    private lateinit var focus: FocusShadow
    private lateinit var clipboard: ClipboardManager

    @Before fun setUp() {
        service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        field = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = "other.app"
            isEditable = true
            isFocused = true
            isVisibleToUser = true
            isEnabled = true
            text = "Com estas?"
        }
        node = Shadow.extract(field)
        focus = Shadow.extract(service)
        focus.field = field
    }

    @After fun tearDown() = service.onDestroy()

    @Test fun directEditorLosingFocusOnRefreshIsRejectedBeforeReadingText() {
        node.focusAfterRefresh = false
        val window = tap()
        assertFalse(field.isFocused)
        assertEquals(1, node.refreshes)
        assertEquals(0, node.textReads)
        assertNull(retainedField())
        // No field check is started, so it cannot open a field history session.
        assertNull(ViewModelProvider(window)[CheckViewModel::class.java].state)
        focusWindow(window)
        // An empty clipboard also leaves no Understand request to send to a provider.
        assertEquals(UnderstandState.Message(false), ViewModelProvider(window)[UnderstandViewModel::class.java].state)
        assertEquals(0, focus.rootQueries)
        assertEquals(1, focus.focusQueries)
    }

    @Test fun rejectedEditorStillAllowsTheCopiedTextFallback() {
        clipboard.setPrimaryClip(ClipData.newPlainText("copied", "Bon dia"))
        node.focusAfterRefresh = false
        val window = tap()
        focusWindow(window)
        assertEquals(0, node.textReads)
        assertNull(retainedField())
        assertNull(ViewModelProvider(window)[CheckViewModel::class.java].state)
        val reading = ViewModelProvider(window)[UnderstandViewModel::class.java].state
        assertEquals("Bon dia", (reading as UnderstandState.Loading).text)
    }

    @Test fun focusedRootlessEditorStillStartsItsFieldCheck() {
        val window = tap()
        assertEquals(1, node.refreshes)
        assertTrue(node.textReads > 0)
        assertSame(field, retainedField())
        val checking = ViewModelProvider(window)[CheckViewModel::class.java].state
        assertEquals("Com estas?", (checking as CardState.Loading).text)
        assertEquals(0, focus.rootQueries)
        assertEquals(1, focus.focusQueries)
    }

    private fun tap(): OverlayWindow {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
        val card = requireNotNull(ReflectionHelpers.getField<Any?>(service, "card"))
        return ReflectionHelpers.getField(card, "window")
    }

    private fun retainedField(): AccessibilityNodeInfo? {
        val card = requireNotNull(ReflectionHelpers.getField<Any?>(service, "card"))
        return ReflectionHelpers.getField(card, "field")
    }

    private fun focusWindow(window: OverlayWindow) {
        ReflectionHelpers.getField<View>(window, "root").dispatchWindowFocusChanged(true)
    }

    @Implements(AccessibilityService::class)
    class FocusShadow : ShadowAccessibilityService() {
        lateinit var field: AccessibilityNodeInfo
        var focusQueries = 0
        var rootQueries = 0
        @Implementation protected fun findFocus(type: Int): AccessibilityNodeInfo {
            assertEquals(AccessibilityNodeInfo.FOCUS_INPUT, type)
            focusQueries++
            return field
        }
        @Implementation protected override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            rootQueries++
            return null
        }
    }

    @Implements(AccessibilityNodeInfo::class)
    class RefreshShadow : ShadowAccessibilityNodeInfo() {
        @RealObject private lateinit var realNode: AccessibilityNodeInfo
        var focusAfterRefresh = true
        var refreshes = 0
        var textReads = 0
        @Implementation protected override fun refresh(): Boolean {
            refreshes++
            realNode.isFocused = focusAfterRefresh
            return true
        }
        @Implementation protected override fun getText(): CharSequence? {
            textReads++
            return super.getText()
        }
    }
}
