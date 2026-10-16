package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.Understander
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.popup.PopupReturn
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.util.Collections

/** Shortcut restoration uses the same retained view models and pending gateway return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class, shadows = [ShortcutRestoreTest.FocusShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutRestoreTest {
    private lateinit var service: LinguizeAccessibilityService
    private lateinit var app: App
    private lateinit var field: AccessibilityNodeInfo
    private lateinit var focus: FocusShadow
    private val checks = FakeClient { ModelAnswer(Verdict(Verdict.Status.Ok, language = "English"), "{}") }
    private val readings = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    @Before fun setUp() {
        service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
        app = App.of(service)
        app.settings.historyEnabled = false
        app.settings.provider = Provider.Gemini
        app.settings.setModel(Provider.Gemini, "first-model")
        app.settings.popupPosition = PopupPosition.Bottom
        // Use real checkers with fake provider credentials; host callbacks and view models remain real.
        val clients = Provider.entries.associateWith { checks }
        ReflectionHelpers.setField(app, "checker\$delegate", lazyOf(
            Checker(app.settings, { "test-key" }, Prompt("instructions", "{}"), clients),
        ))
        val reader = object : ProviderClient by checks {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                val text = Json.parseToJsonElement(request.user).jsonObject.getValue("message").jsonPrimitive.content
                readings += model to text
                return """{"translation":"Translated","notes":[]}"""
            }
        }
        ReflectionHelpers.setField(app, "understander\$delegate", lazyOf(
            Understander(app.settings, { "test-key" }, Provider.entries.associateWith { reader }),
        ))
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        field = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = "other.app"
            isEditable = true
            isFocused = true
            isEnabled = true
            isVisibleToUser = true
            text = "Original writing"
        }
        shadowOf(field).setRefreshReturnValue(true)
        focus = Shadow.extract(service)
        focus.field = field
    }

    @After fun tearDown() = service.onDestroy()

    @Test fun androidButtonCallbackOpensTheFocusedWhatsAppText() {
        field.packageName = "com.whatsapp"
        field.viewIdResourceName = "com.whatsapp:id/entry"
        field.text = "Pues... potser haurem de experimentat viure com abans "
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        // Exercise Android's registered callback, not a reflective call to tapped().
        ReflectionHelpers.callInstanceMethod<Unit>(service.accessibilityButtonController, "dispatchAccessibilityButtonClicked")
        waitFor { ReflectionHelpers.getField<Any?>(service, "card") != null }
        val check = ViewModelProvider(window())[CheckViewModel::class.java]
        waitFor { check.state is CardState.Done }
        assertEquals(listOf("Pues... potser haurem de experimentat viure com abans"), checks.checks.map { it.third.text })
    }

    @Test fun hiddenCheckRefreshesPositionAndContextAndKeepsTheLaterAppReturn() {
        tap()
        val original = window()
        val check = ViewModelProvider(original)[CheckViewModel::class.java]
        waitFor { check.state is CardState.Done }
        val token = detour()
        app.settings.popupPosition = PopupPosition.Top
        app.settings.setModel(Provider.Gemini, "second-model")
        tap()
        waitFor { checks.checks.size == 2 && check.state is CardState.Done }
        assertSame(original, window())
        assertFalse(original.hidden)
        assertEquals(PopupPosition.Top, position())
        assertEquals(listOf("first-model", "second-model"), checks.checks.map { it.second })
        assertEquals(List(2) { "Original writing" }, checks.checks.map { it.third.text })

        // Manually revealing the card must leave the gateway callback waiting.
        app.settings.setModel(Provider.Gemini, "third-model")
        PopupReturn.left(token)
        waitFor { checks.checks.size == 3 && check.state is CardState.Done }
        assertEquals("third-model", checks.checks.last().second)
        detour()
        tap()
        drain()
        assertEquals(3, checks.checks.size) // Unchanged context is a no-op.
    }

    @Test fun hiddenUnderstandUsesCapturedTextAndOnlyReconcilesItsActiveMode() {
        field.text = ""
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("copied", "Captured message"))
        tap()
        val original = window()
        ReflectionHelpers.getField<View>(original, "root").dispatchWindowFocusChanged(true)
        val understand = ViewModelProvider(original)[UnderstandViewModel::class.java]
        waitFor { understand.state is UnderstandState.Done }
        detour()
        clipboard.setPrimaryClip(ClipData.newPlainText("changed", "Different clipboard"))
        app.settings.setModel(Provider.Gemini, "second-model")
        app.settings.popupPosition = PopupPosition.Middle
        tap()
        waitFor { readings.size == 2 && understand.state is UnderstandState.Done }
        assertSame(original, window())
        assertEquals(PopupPosition.Middle, position())
        assertEquals(listOf("first-model" to "Captured message", "second-model" to "Captured message"), readings.toList())
        assertTrue(checks.checks.isEmpty())
        detour()
        tap()
        drain()
        assertEquals(2, readings.size)
    }

    @Test fun shownFailedCardDoesNotRetryAndClosedCardCannotReturnLater() {
        checks.answer = { throw CheckFailure(CheckFailure.Reason.Offline) }
        tap()
        val original = window()
        val check = ViewModelProvider(original)[CheckViewModel::class.java]
        waitFor { check.state is CardState.Failed }
        app.settings.setModel(Provider.Gemini, "second-model")
        tap()
        drain()
        assertSame(original, window())
        assertEquals(1, checks.checks.size)
        val token = detour()
        ReflectionHelpers.getField<() -> Unit>(card(), "discard").invoke()
        PopupReturn.left(token)
        drain()
        assertNull(ReflectionHelpers.getField<Any?>(service, "card"))
        assertTrue(original.hidden)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertEquals(1, checks.checks.size)
    }

    private fun tap() = ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
    private fun card(): Any = requireNotNull(ReflectionHelpers.getField<Any?>(service, "card"))
    private fun window(): OverlayWindow = ReflectionHelpers.getField(card(), "window")
    private fun restore(): () -> Unit = ReflectionHelpers.getField(card(), "restore")

    /** Simulate the still-open gateway without starting the separate app task. */
    private fun detour(): Long = PopupReturn.await(restore()).also {
        window().hidden = true
        focus.field = AccessibilityNodeInfo.obtain(View(service)).apply { packageName = service.packageName }
    }

    /** Observe the retained Compose position state captured by the real restore callback. */
    private fun position(): PopupPosition = restore().let { callback ->
        callback.javaClass.declaredFields.mapNotNull { member ->
            member.isAccessible = true
            (member.get(callback) as? MutableState<*>)?.value as? PopupPosition
        }.single()
    }

    private fun drain() = repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("Timed out waiting for the retained card", done())
    }

    @Implements(AccessibilityService::class)
    class FocusShadow : ShadowAccessibilityService() {
        lateinit var field: AccessibilityNodeInfo
        @Implementation protected fun findFocus(type: Int): AccessibilityNodeInfo = field
    }
}
