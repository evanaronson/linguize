package com.evanaronson.linguize.accessibility

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusedFieldTest {
    private fun node(
        focused: Boolean = false,
        editable: Boolean = false,
        app: String = "com.openai.chatgpt",
        window: Int = 7,
    ): AccessibilityNodeInfo {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return AccessibilityNodeInfo.obtain(View(context)).apply {
            packageName = app
            isVisibleToUser = true
            isEnabled = true
            isFocused = focused
            isEditable = editable
            val info = AccessibilityWindowInfo.obtain()
            shadowOf(info).setId(window)
            shadowOf(this).setAccessibilityWindowInfo(info)
        }
    }

    private fun children(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        children.forEach { shadowOf(parent).addChild(it) }
    }

    @Test fun missingGlobalFocusCanUseTheActiveWindowsFocusedEditor() {
        val root = node()
        val editor = node(focused = true, editable = true)
        assertEquals(editor, FocusedField.resolve(root, null) { editor }.field)
    }

    @Test fun missingFocusQueriesCanUseAUniqueFocusedEditorInTheRoot() {
        val root = node()
        val editor = node(focused = true, editable = true)
        children(root, editor)
        assertEquals(editor, FocusedField.resolve(root, null) { null }.field)
    }

    @Test fun windowFocusAvoidsScanningALargeConversationWhenGlobalFocusIsAContainer() {
        val root = node()
        val source = node()
        val editor = node(focused = true, editable = true)
        children(root, source, editor)
        repeat(150) { children(source, node()) }
        assertEquals(editor, FocusedField.resolve(root, source) { editor }.field)
    }

    @Test fun directFocusedEditorDoesNotNeedAnActiveRootOrAnotherFocusQuery() {
        val editor = node(focused = true, editable = true)
        assertEquals(editor, FocusedField.resolve(null, editor) { error("Direct focus is sufficient") }.field)
    }

    @Test fun missingGlobalFocusNeverChoosesAnUnfocusedEditor() {
        val root = node()
        children(root, node(editable = true))
        assertNull(FocusedField.resolve(root, null) { null }.field)
    }

    @Test fun privateSourceCannotBeBypassedByTheWindowFocusQuery() {
        val root = node()
        val source = node().apply { isPassword = true }
        assertNull(FocusedField.resolve(root, source) { error("Private source stops lookup") }.field)
    }

    @Test fun windowFocusCannotCrossIntoAnotherAppOrWindow() {
        val root = node()
        assertNull(FocusedField.resolve(root, null) {
            node(focused = true, editable = true, app = "other.app")
        }.field)
        assertNull(FocusedField.resolve(root, null) {
            node(focused = true, editable = true, window = 8)
        }.field)
    }

    @Test fun windowFocusCannotBypassPrivateFields() {
        val root = node()
        val editor = node(focused = true, editable = true).apply { hintText = "Password" }
        assertNull(FocusedField.resolve(root, null) { editor }.field)
    }

    @Test fun onlyUsableNonPrivateWritingTakesPriority() {
        assertNull(FocusedField.selection(null))
        val editor = node(focused = true, editable = true)
        editor.text = "   "
        assertNull(FocusedField.selection(editor))
        editor.text = "Com estas?"
        assertEquals("Com estas?", FocusedField.selection(editor)!!.text)
        editor.isShowingHintText = true
        assertNull(FocusedField.selection(editor))
        editor.isShowingHintText = false
        editor.isPassword = true
        assertNull(FocusedField.selection(editor))
    }

    @Test fun hintExposedAsTextWithoutTheHintFlagDoesNotBecomeWriting() {
        val editor = node(focused = true, editable = true).apply {
            text = "Message"
            hintText = "Message"
            isShowingHintText = false
        }
        assertNull(FocusedField.selection(editor))
        // Native empty editors can expose a 0,0 cursor alongside their hint.
        editor.setTextSelection(0, 0)
        assertNull(FocusedField.selection(editor))
        editor.text = "  Message  "
        assertNull(FocusedField.selection(editor))
        editor.text = "Escribe un mensaje"
        editor.hintText = "Escribe un mensaje"
        assertNull(FocusedField.selection(editor))
        // Valid text/cursor metadata means the user really typed the same word as the hint.
        editor.text = "Message"
        editor.hintText = "Message"
        editor.setTextSelection(7, 7)
        assertEquals("Message", FocusedField.selection(editor)!!.text)
        // Actual writing at the start still exposes selection actions on Android TextView.
        editor.setTextSelection(0, 0)
        editor.addAction(AccessibilityNodeInfo.ACTION_SET_SELECTION)
        assertEquals("Message", FocusedField.selection(editor)!!.text)
        editor.hintText = "Write a message"
        assertEquals("Message", FocusedField.selection(editor)!!.text)
    }

    @Test fun resolvesFocusedChildOfNonEditableSource() {
        val root = node()
        val source = node()
        val editor = node(focused = true, editable = true)
        children(root, source)
        children(source, editor)
        assertEquals(editor, FocusedField.find(root, source).field)
    }

    @Test fun setTextActionCanIdentifyFocusedEditorWithoutEditableFlag() {
        val root = node()
        val editor = node(focused = true).apply { addAction(AccessibilityNodeInfo.ACTION_SET_TEXT) }
        children(root, editor)
        assertEquals(editor, FocusedField.find(root, root).field)
    }

    @Test fun loneUnfocusedEditorIsNeverChosen() {
        val root = node()
        children(root, node(editable = true))
        val result = FocusedField.find(root, root)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("No focused editor exposed"))
    }

    @Test fun multipleFocusedEditorsAreAmbiguous() {
        val root = node()
        children(root, node(focused = true, editable = true), node(focused = true, editable = true))
        val result = FocusedField.find(root, root)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("Ambiguous input focus"))
    }

    @Test fun focusedPrivateFieldStopsLookupEvenWithAnotherCandidate() {
        val root = node()
        children(root, node(focused = true, editable = true),
            node(focused = true).apply { isPassword = true })
        val result = FocusedField.find(root, root)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("Field looks private"))
    }

    @Test fun privateSourceCannotBeBypassed() {
        val root = node()
        val source = node().apply { hintText = "Password" }
        children(root, source, node(focused = true, editable = true))
        assertNull(FocusedField.find(root, source).field)
    }

    @Test fun changedAppOrWindowIsRejected() {
        val source = node()
        assertNull(FocusedField.find(node(focused = true, editable = true, app = "other.app"), source).field)
        assertNull(FocusedField.find(node(focused = true, editable = true, window = 8), source).field)
    }

    @Test fun foreignAndHiddenNodesCannotBeCandidates() {
        val root = node()
        children(root,
            node(focused = true, editable = true, app = "other.app"),
            node(focused = true, editable = true, window = 8),
            node(focused = true, editable = true).apply { isVisibleToUser = false },
            node(focused = true, editable = true).apply { isEnabled = false })
        assertNull(FocusedField.find(root, root).field)
    }

    @Test fun incompleteScanCannotChooseAnEarlyCandidate() {
        val root = node()
        var tail = node(focused = true, editable = true)
        children(root, tail)
        repeat(130) {
            val next = node()
            children(tail, next)
            tail = next
        }
        val result = FocusedField.find(root, root)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("Lookup limit reached"))
    }

    @Test fun focusedSubtreeResolvesWithoutScanningLargeConversation() {
        val root = node()
        val source = node()
        val editor = node(focused = true, editable = true)
        val conversation = node()
        children(root, conversation, source)
        children(source, editor)
        repeat(150) { children(conversation, node()) }
        // A full-window scan would stop at its limit. The completed focused subtree is enough.
        assertEquals(editor, FocusedField.find(root, source).field)
    }

    @Test fun emptySourceSubtreeCanFallBackToSameWindow() {
        val root = node()
        val source = node()
        val editor = node(focused = true, editable = true)
        children(root, source, editor)
        assertEquals(editor, FocusedField.find(root, source).field)
    }

    @Test fun ambiguousSourceSubtreeDoesNotFallBackElsewhere() {
        val root = node()
        val source = node()
        children(root, source, node(focused = true, editable = true))
        children(source, node(focused = true, editable = true), node(focused = true, editable = true))
        val result = FocusedField.find(root, source)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("Ambiguous input focus"))
    }

    @Test fun incompleteSourceSubtreeDoesNotFallBackElsewhere() {
        val root = node()
        val source = node()
        children(root, source, node(focused = true, editable = true))
        repeat(130) { children(source, node()) }
        val result = FocusedField.find(root, source)
        assertNull(result.field)
        assertTrue(result.reason.startsWith("Lookup limit reached"))
    }
}
