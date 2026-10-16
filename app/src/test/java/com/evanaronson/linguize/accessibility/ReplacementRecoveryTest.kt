package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.popup.PopupReturn
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** Exercises the service's real close/write/recovery callbacks, without a provider request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class, shadows = [ReplacementRecoveryTest.FocusShadow::class])
class ReplacementRecoveryTest {
    private lateinit var service: LinguizeAccessibilityService
    private lateinit var field: AccessibilityNodeInfo
    private lateinit var clipboard: ClipboardManager
    private val writes = mutableListOf<String>()
    private var acceptWrite = false

    // Both previews differ from the accepted-only text, including another unaccepted fix.
    private val revision = Revision("a b c", listOf(
        Edit(0, EditKind.Fix, 0, 1, "a", "A", null),
        Edit(1, EditKind.Fix, 2, 3, "b", "B", null),
        Edit(2, EditKind.Natural, 4, 5, "c", "C", null),
    )).accept(0)

    @Before fun setUp() {
        service = Robolectric.buildService(LinguizeAccessibilityService::class.java).create().get()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "leave this alone"))
        field = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = "other.app"
            isEditable = true
            isFocused = true
            isVisibleToUser = true
            isEnabled = true
            // Opening uses the original text, with no configured key or live provider.
            text = ""
        }
        shadowOf(field).setRefreshReturnValue(true)
        shadowOf(field).setOnPerformActionListener { action, arguments ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                writes += arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()
                acceptWrite
            } else true
        }
        Shadow.extract<FocusShadow>(service).field = field
    }

    @After fun tearDown() = service.onDestroy()

    /** Seed a reviewed revision after opening, keeping the real host callbacks and source selection. */
    private fun review(accepted: Boolean = true): Pair<OverlayWindow, CheckViewModel> {
        field.text = revision.original
        ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
        val window = currentWindow()
        val check = ViewModelProvider(window)[CheckViewModel::class.java]
        // Stop the no-key check before seeding deterministic accepted text. No live model is used.
        check.dismiss(applied = false)
        val state = ReflectionHelpers.getField<MutableState<CardState?>>(check, "state\$delegate")
        state.value = CardState.Done(CheckResult.Reviewed(
            if (accepted) revision else revision.copy(steps = emptyList()), EditKind.entries,
        ))
        assertNotEquals(revision.workingText, revision.preview(EditKind.Fix).text)
        assertNotEquals(revision.workingText, revision.preview(EditKind.Natural).text)
        return window to check
    }

    @Test fun changedFieldKeepsExactAcceptedTextForExplicitCopy() {
        val (original, check) = review()
        field.text = "changed in the app"
        back(original)
        assertTrue(writes.isEmpty())
        assertRecovery(original, check)
        val recovery = currentWindow()
        click(R.string.shortcut_copy_accepted)
        assertEquals(revision.workingText, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertSame(recovery, currentWindow())
        assertEquals(Lifecycle.State.RESUMED, recovery.lifecycle.currentState)
        assertTrue(writes.isEmpty())
        click(R.string.shortcut_dismiss)
        assertNull(card())
    }

    @Test fun staleNodeKeepsTextAndDismissDoesNotCopyOrWrite() {
        val (original, check) = review()
        shadowOf(field).setRefreshReturnValue(false)
        back(original)
        assertRecovery(original, check)
        click(R.string.shortcut_dismiss)
        assertNull(card())
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(writes.isEmpty())
    }

    @Test fun refusedWriteKeepsTextAndBackDoesNotRetryIt() {
        val (original, check) = review()
        back(original)
        assertEquals(listOf(revision.workingText), writes)
        assertRecovery(original, check)
        back(currentWindow())
        assertNull(card())
        assertEquals(listOf(revision.workingText), writes)
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun successfulWriteClosesNormallyWithoutRecovery() {
        val (original, check) = review()
        acceptWrite = true
        back(original)
        assertNull(card())
        assertNull(check.state)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertEquals(listOf(revision.workingText), writes)
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun nothingAcceptedClosesWithoutWriteOrRecovery() {
        val (original, check) = review(accepted = false)
        back(original)
        assertNull(card())
        assertNull(check.state)
        assertTrue(writes.isEmpty())
    }

    @Test fun failureToAttachRecoveryStillDestroysTheOriginalReview() {
        val (original, check) = review()
        val unavailable = object : WindowManager by service.getSystemService(WindowManager::class.java) {
            override fun addView(view: View, params: ViewGroup.LayoutParams) {
                throw SecurityException("Overlay unavailable")
            }
        }
        ReflectionHelpers.setField(service, "mWindowManager", unavailable)
        back(original)
        assertNull(card())
        assertNull(check.state)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertEquals(service.getString(R.string.shortcut_recovery_failed), ShadowToast.getTextOfLatestToast())
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun shortcutRestoresRecoveryWithoutRevivingItsCheckAndCopyKeepsItOpen() {
        val (original, check) = review()
        back(original)
        val recovery = currentWindow()
        // Home/Recents returns to the original editor without finishing the app gateway.
        val returnToken = PopupReturn.await(ReflectionHelpers.getField(card()!!, "restore"))
        recovery.hidden = true
        ReflectionHelpers.callInstanceMethod<Unit>(service, "tapped")
        assertSame(recovery, currentWindow())
        assertFalse(recovery.hidden)
        assertNull(check.state)
        assertTrue(recovery.viewModelStore.keys().isEmpty())
        assertEquals(listOf(revision.workingText), writes)
        click(R.string.shortcut_copy_accepted)
        assertSame(recovery, currentWindow())
        assertEquals(revision.workingText, clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(R.string.shortcut_dismiss)
        PopupReturn.left(returnToken)
        assertNull(card())
    }

    @Test fun recoveryUsesLockVisibilityAndIsDestroyedWithTheService() {
        val (original, check) = review()
        back(original)
        assertRecovery(original, check)
        val recovery = currentWindow()
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        recovery.hidden = true
        recovery.hidden = false
        assertEquals(Lifecycle.State.STARTED, recovery.lifecycle.currentState)
        service.onDestroy()
        assertEquals(Lifecycle.State.DESTROYED, recovery.lifecycle.currentState)
        assertNull(card())
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    private fun assertRecovery(original: OverlayWindow, check: CheckViewModel) {
        assertNull(check.state)
        assertNull(check.workingText)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertNotSame(original, currentWindow())
        assertTrue(currentWindow().viewModelStore.keys().isEmpty())
        assertNull(ReflectionHelpers.getField<AccessibilityNodeInfo?>(card()!!, "field"))
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == revision.workingText } == true })
    }

    private fun card(): Any? = ReflectionHelpers.getField(service, "card")
    private fun currentWindow(): OverlayWindow = ReflectionHelpers.getField(card()!!, "window")
    private fun back(window: OverlayWindow) = ReflectionHelpers.getField<() -> Unit>(window, "onBack").invoke()

    /** Use the actual Compose semantics actions, without adding a second UI test framework. */
    private fun nodes(): List<SemanticsNode> {
        val root = ReflectionHelpers.getField<View>(currentWindow(), "root")
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idle()
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(root)).rootSemanticsNode)
    }

    private fun click(label: Int) {
        val text = service.getString(label)
        val node = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true &&
            it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    @Implements(AccessibilityService::class)
    class FocusShadow : ShadowAccessibilityService() {
        lateinit var field: AccessibilityNodeInfo
        @Implementation protected fun findFocus(type: Int): AccessibilityNodeInfo = field
    }
}
