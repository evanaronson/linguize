package com.evanaronson.linguize.accessibility

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Lab, Replying to: the messages visible above the writer's field in the app behind the card,
 * read only when the writer taps Pick their message. Nothing is kept: the caller shows them on
 * the card and the writer picks one. It doesn't guess whose they are.
 *
 * Read from the field's own window (its ancestors, not a window list: the service asks for no
 * retrieval flags): visible, non-editable nodes with text that end above the field's top,
 * in screen order, ignoring buttons and text with no letters (times, counts).
 */
internal object ConversationOnScreen {
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 64
    private const val TIME_LIMIT_MS = 150L

    /** The texts above [field] in its window, oldest first; empty when nothing can be read. */
    fun above(field: AccessibilityNodeInfo?): List<String> {
        if (field == null) return emptyList()
        return try {
            if (!field.refresh() || !field.isVisibleToUser) return emptyList()
            messages(rootOf(field), field)
        } catch (_: RuntimeException) {
            // A window that went away mid-read reads as nothing, never as a crash.
            emptyList()
        }
    }

    private fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        repeat(MAX_DEPTH) { current = current.parent ?: return current }
        return current
    }

    /** Texts in [root]'s window that end above [field], in screen order (top to bottom). */
    fun messages(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo): List<String> {
        val fieldTop = Rect().also(field::getBoundsInScreen).top
        val found = mutableListOf<Pair<Int, String>>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val deadline = SystemClock.uptimeMillis() + TIME_LIMIT_MS
        var scanned = 0
        while (pending.isNotEmpty() && scanned < MAX_NODES && SystemClock.uptimeMillis() <= deadline) {
            // Depth first, children in order: the tree's own reading order.
            val node = pending.removeFirst()
            scanned++
            if (!node.isVisibleToUser || !FocusedField.sameWindow(node, field)) continue
            // Fields (the writer's own and any other) are never read.
            if (FocusedField.supportsEditing(node) || FocusedField.isPrivate(node)) continue
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && !bounds.isEmpty && bounds.bottom <= fieldTop &&
                !isButton(node) && text.any(Char::isLetter)) {
                found += bounds.top to text
            }
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let(pending::addFirst)
            }
        }
        return found
            .sortedBy { it.first }
            .map { it.second }
            // A bubble and its text node often both carry the same text.
            .fold(mutableListOf()) { kept, text -> kept.apply { if (lastOrNull() != text) add(text) } }
    }

    private fun isButton(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.let { it.endsWith("Button") } == true
}
