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

    fun sameWindow(node: AccessibilityNodeInfo, source: AccessibilityNodeInfo): Boolean =
        !source.packageName.isNullOrEmpty() &&
            node.packageName?.toString() == source.packageName?.toString() &&
            node.windowId == source.windowId

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
        var editors = 0
        var candidates = 0
        var candidate: AccessibilityNodeInfo? = null
        fun stopped(reason: String) = Result(null,
            "$reason · scanned: $scanned · editors: $editors · focused editors: $candidates")

        while (pending.isNotEmpty()) {
            if (scanned >= 128 || SystemClock.uptimeMillis() >= deadline) {
                return stopped("Lookup limit reached")
            }
            val node = pending.removeFirst()
            scanned++
            if (!seen.add(node) || !sameWindow(node, source)) continue
            val active = node.isVisibleToUser && node.isEnabled && node.isFocused
            if (active && isPrivate(node)) return stopped("Field looks private")
            if (supportsEditing(node)) {
                editors++
                if (active) {
                    candidates++
                    candidate = node
                }
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
