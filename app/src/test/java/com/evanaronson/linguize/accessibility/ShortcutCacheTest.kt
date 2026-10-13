package com.evanaronson.linguize.accessibility

import android.app.Application
import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.evanaronson.linguize.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** Uses Android's actual focus cache; only the remote editor query is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShortcutCacheTest.FocusServiceShadow::class])
class ShortcutCacheTest {
    @Test fun switchingChatsDoesNotSpendTheFirstTapOnThePreviousEditor() {
        val service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
        val client = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
        val cacheType = Class.forName("android.view.accessibility.AccessibilityCache")
        val refresherType = Class.forName("android.view.accessibility.AccessibilityCache\$AccessibilityNodeRefresher")
        val cache = cacheType.getConstructor(refresherType).newInstance(refresherType.getConstructor().newInstance())
        val connection = 812
        client.getMethod("setCache", Int::class.javaPrimitiveType, cacheType).invoke(null, connection, cache)
        ReflectionHelpers.setField(service, "mConnectionId", connection)
        val focus = Shadow.extract<FocusServiceShadow>(service)
        focus.cache = cache
        try {
            for (private in listOf(true, false)) {
                val oldEditor = editor(private = false)
                cacheType.getMethod("add", AccessibilityNodeInfo::class.java).invoke(cache, oldEditor)
                focus.oldNodeId = oldEditor.javaClass.getMethod("getSourceNodeId").invoke(oldEditor) as Long
                assertNotNull(focus.cachedFocus())
                // Switching chats replaces the editor but leaves old focus cached.
                // Handle the new private or empty editor on THIS tap.
                focus.currentEditor = editor(private = private)
                val before = focus.queries
                ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
                assertEquals(before + 1, focus.queries)
                assertNull(focus.cachedFocus())
                val feedback = if (private) R.string.shortcut_private_field else R.string.shortcut_no_text
                assertTrue(ShadowToast.getTextOfLatestToast().toString().contains(service.getString(feedback)))
            }
        } finally {
            client.getMethod("removeConnection", Int::class.javaPrimitiveType).invoke(null, connection)
            service.onDestroy()
        }
    }

    private fun editor(private: Boolean): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication())).apply {
            packageName = "com.openai.chatgpt"
            isEditable = true
            isFocused = true
            isEnabled = true
            isVisibleToUser = true
            isPassword = private
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(7)
            shadowOf(this).setAccessibilityWindowInfo(window)
        }

    @Implements(AccessibilityService::class)
    class FocusServiceShadow : ShadowAccessibilityService() {
        lateinit var cache: Any
        lateinit var currentEditor: AccessibilityNodeInfo
        var oldNodeId = 0L
        var queries = 0

        fun cachedFocus(): AccessibilityNodeInfo? = cache.javaClass.getMethod(
            "getFocus", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(cache, AccessibilityNodeInfo.FOCUS_INPUT, oldNodeId, -2) as AccessibilityNodeInfo?

        @Implementation
        protected fun findFocus(type: Int): AccessibilityNodeInfo? {
            assertEquals(AccessibilityNodeInfo.FOCUS_INPUT, type)
            queries++
            return cachedFocus() ?: currentEditor
        }
    }
}
