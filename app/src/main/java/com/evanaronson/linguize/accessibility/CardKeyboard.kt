package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityService

/**
 * The keyboard while the accessibility button's card is open (docs/accessibility-spec.md,
 * "The keyboard"). The card first shows with no keyboard: the app below may have had its own
 * keyboard up (the writer was typing in it), and that keyboard would stay up under the card,
 * typing into the app, not the card. So the service asks Android to keep the soft keyboard
 * hidden ([AccessibilityService.SoftKeyboardController.SHOW_MODE_HIDDEN]) while the card shows,
 * and gives the writer's own show mode back the moment a card field takes focus (the field then
 * opens the keyboard for itself), when the card hides for a detour, and when it closes.
 *
 * One per service: windows never overlap, but if they did, a second [closeSource] while holding
 * changes nothing, so the first [release] still restores the writer's mode, never ours.
 */
internal class CardKeyboard(private val controller: () -> AccessibilityService.SoftKeyboardController?) {
    /** The writer's show mode to give back; null while nothing is held. */
    private var restoreTo: Int? = null

    /** Whether the source keyboard is being kept closed. */
    val holding: Boolean get() = restoreTo != null

    /** Closes the app's keyboard below the card, and keeps it closed until [release]. */
    fun closeSource() {
        if (restoreTo != null) return
        val keyboard = controller() ?: return
        val mode = runCatching { keyboard.showMode }.getOrNull() ?: return
        if (mode == AccessibilityService.SHOW_MODE_HIDDEN) return
        if (runCatching { keyboard.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN) }.getOrDefault(false)) restoreTo = mode
    }

    /** Gives the writer's show mode back; the keyboard can show again (for a card field, or the app). */
    fun release() {
        val mode = restoreTo ?: return
        restoreTo = null
        runCatching { controller()?.setShowMode(mode) }
    }
}
