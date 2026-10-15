package com.evanaronson.linguize.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** A tap-only fallback. Inspects metadata, never writing or other node contents. */
internal object FocusedField {
    data class Result(val field: AccessibilityNodeInfo?, val reason: String)

    fun supportsEditing(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    fun isPrivate(node: AccessibilityNodeInfo): Boolean =
        node.isPassword || PrivateField.matches(node.inputType, node.hintText, node.viewIdResourceName)

    /** Privacy is checked before reading text. Empty/hint fields do not outrank copied text. */
    fun selection(node: AccessibilityNodeInfo?): com.evanaronson.linguize.core.Selection? {
        if (node == null || !node.isVisibleToUser || !node.isEnabled ||
            !supportsEditing(node) || isPrivate(node) || node.isShowingHintText) return null
        val text = node.text?.toString() ?: return null
        // Some apps expose their hint as text without setting isShowingHintText.
        // Match the field's own metadata, never a global list of generic words.
        val hint = node.hintText?.toString()?.trim()
        val hasWriting = node.textSelectionStart > 0 || node.textSelectionEnd > 0 ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
        if (!hint.isNullOrEmpty() && text.trim() == hint && !hasWriting) return null
        return text
            .let { com.evanaronson.linguize.core.Selection.of(it, node.textSelectionStart, node.textSelectionEnd) }
            ?.takeIf { it.text.isNotEmpty() }
    }

    fun sameWindow(node: AccessibilityNodeInfo, source: AccessibilityNodeInfo): Boolean =
        !source.packageName.isNullOrEmpty() &&
            node.packageName?.toString() == source.packageName?.toString() &&
            node.windowId == source.windowId

    /** The global query can briefly miss focus, or return an editor's container. */
    fun resolve(
        root: AccessibilityNodeInfo?,
        source: AccessibilityNodeInfo?,
        windowFocus: () -> AccessibilityNodeInfo? = { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
    ): Result {
        val anchor = source ?: root ?: return Result(null, "Source root unavailable")
        if (isPrivate(anchor)) return Result(null, "Field looks private")
        // Preserve the direct lookup, including apps that do not expose a usable root.
        if (source != null && supportsEditing(source)) return Result(source, "Focused editor found")
        if (root == null) return Result(null, "Source root unavailable")
        if (!sameWindow(root, anchor)) return Result(null, "Source window changed")

        val local = windowFocus()
        if (local != null) {
            if (!sameWindow(local, root)) return Result(null, "Source window changed")
            if (isPrivate(local)) return Result(null, "Field looks private")
            if (local.isFocused && local.isVisibleToUser && local.isEnabled && supportsEditing(local)) {
                return Result(local, "Focused editor found")
            }
            return find(root, local)
        }
        return find(root, anchor)
    }

    fun find(root: AccessibilityNodeInfo?, source: AccessibilityNodeInfo): Result {
        if (isPrivate(source)) return Result(null, "Field looks private")
        if (root == null) return Result(null, "Source root unavailable")
        if (!sameWindow(root, source)) return Result(null, "Source window changed")

        // Android may return the editor's container rather than the editor itself.
        // Finish that focused subtree before considering the rest of a large conversation.
        val local = scan(source, source)
        if (source == root || !local.reason.startsWith("No focused editor exposed")) return local
        return scan(root, source)
    }

    private fun scan(root: AccessibilityNodeInfo, source: AccessibilityNodeInfo): Result {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val seen = HashSet<AccessibilityNodeInfo>()
        pending.add(root)
        val deadline = SystemClock.uptimeMillis() + 150
        var scanned = 0
        var candidates = 0
        var candidate: AccessibilityNodeInfo? = null
        fun stopped(reason: String) = Result(null, reason)

        while (pending.isNotEmpty()) {
            if (scanned >= 128 || SystemClock.uptimeMillis() >= deadline) {
                return stopped("Lookup limit reached")
            }
            val node = pending.removeFirst()
            scanned++
            if (!seen.add(node) || !sameWindow(node, source)) continue
            val active = node.isVisibleToUser && node.isEnabled && node.isFocused
            if (active && isPrivate(node)) return stopped("Field looks private")
            if (active && supportsEditing(node)) {
                candidates++
                candidate = node
            }
            // A wide tree is also bounded. Never accept a candidate from a partial scan.
            if (node.childCount > 128 - scanned - pending.size) return stopped("Lookup limit reached")
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.addLast(it) }
            }
        }
        if (SystemClock.uptimeMillis() >= deadline) return stopped("Lookup limit reached")
        return if (candidates == 1) Result(candidate, "Focused editor found")
        else stopped(if (candidates == 0) "No focused editor exposed" else "Ambiguous input focus")
    }
}
