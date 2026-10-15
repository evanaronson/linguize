package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.compose.material3.Text
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.readCopiedText
import com.evanaronson.linguize.ui.theme.AppTheme

/** Uses the real overlay and reader against an external app's clipboard on an emulator. */
class ClipboardProbeService : AccessibilityService() {
    private var window: OverlayWindow? = null
    override fun onServiceConnected() {
        val before = readCopiedText()
        val overlay = OverlayWindow(this, onBack = {})
        window = overlay
        overlay.show { AppTheme { Text("Clipboard focus probe") } }
        overlay.whenFocused(onReady = {
            val after = readCopiedText()
            val matches = after is ClipboardText.Text && after.value == "Bon dia. Probe only."
            filesDir.resolve("clipboard-probe.txt").writeText("before=$before\nfocused=$after\nmatches=$matches\n")
            overlay.remove()
            window = null
        }, onUnavailable = {
            filesDir.resolve("clipboard-probe.txt").writeText("focus unavailable\nmatches=false\n")
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        window?.remove()
        window = null
        super.onDestroy()
    }
}
