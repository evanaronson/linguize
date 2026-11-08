package com.evanaronson.linguize.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lab: Help me reply. Puts a drafted reply into the message field the card opened from, which
 * was empty then (nothing to check, so the card read copied text instead).
 */
internal object ReplyPutBack {
    /**
     * Whether [now] still holds only what the field held when the card opened ([openedWith]),
     * or nothing at all. Anything the writer typed since is never overwritten.
     */
    fun untouched(now: String?, showingHint: Boolean, openedWith: String?): Boolean =
        showingHint || now.isNullOrBlank() || now == openedWith

    /** Returns true only when the app accepted the text. */
    fun into(field: AccessibilityNodeInfo, openedWith: String?, reply: String): Boolean {
        if (!field.refresh() || !FocusedField.supportsEditing(field) || FocusedField.isPrivate(field)) return false
        if (!untouched(field.text?.toString(), field.isShowingHintText, openedWith)) return false
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) return false
        val cursorAt = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, reply.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, reply.length)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorAt)
        return true
    }
}
