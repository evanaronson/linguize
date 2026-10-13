package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.components.MenuState
import com.evanaronson.linguize.ui.popup.PopupAppActivity
import com.evanaronson.linguize.ui.popup.PopupReturn
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * Checks text in apps whose selection menu doesn't show Linguize, such as
 * Telegram. Tapping the accessibility button reads the focused text field (the
 * selection, or all of it), floats the result card over the app, and writes the
 * accepted changes back into the field when the card closes. Nothing is read
 * except when the button is tapped.
 */
class LinguizeAccessibilityService : AccessibilityService() {
    /** The card on screen, or waiting hidden during an external detour; null when there's none. */
    private var card: Card? = null

    /**
     * An open card: its [window], the [field] it will write back to, and [discard], which
     * closes it without writing anything back.
     */
    private class Card(val window: OverlayWindow, val field: AccessibilityNodeInfo, val discard: () -> Unit)

    override fun onServiceConnected() {
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) = tapped()
                },
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.shortcut_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun tapped() {
        try {
            open()
        } catch (_: RuntimeException) {
            try {
                card?.discard()
            } catch (_: RuntimeException) {
                // Cleanup must not prevent feedback about the failed check.
            }
            Toast.makeText(this, R.string.shortcut_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // Closed as nothing applied, and no longer waiting for the app.
        card?.discard()
        card = null
        super.onDestroy()
    }

    private fun open() {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        card?.let { current ->
            // Tapping the button while the card waits behind the app brings it back, unless
            // it was left there: it waited too long, or the tap is for another app's field.
            if (!current.window.hidden || !current.leftBehind(focused)) {
                current.window.hidden = false
                return
            }
            current.discard()
        }

        // Preserve the working direct lookup; only fall back within its source window.
        var resolved = focused?.takeIf { FocusedField.supportsEditing(it) }
        if (resolved == null && focused != null) {
            val result = FocusedField.find(rootInActiveWindow, focused)
            if (result.reason == "Field looks private") {
                Toast.makeText(this, R.string.shortcut_private_field, Toast.LENGTH_LONG).show()
                return
            }
            resolved = result.field
            if (resolved != null && (!resolved.refresh() || !resolved.isFocused || !resolved.isVisibleToUser || !resolved.isEnabled ||
                !FocusedField.supportsEditing(resolved) || !FocusedField.sameWindow(resolved, focused) ||
                rootInActiveWindow?.let { FocusedField.sameWindow(it, focused) } != true)) {
                resolved = null
            }
        }
        if (resolved == null) {
            Toast.makeText(this, R.string.shortcut_no_editor, Toast.LENGTH_LONG).show()
            return
        }
        val field = resolved
        if (field.isPassword || PrivateField.matches(field.inputType, field.hintText, field.viewIdResourceName)) {
            Toast.makeText(this, R.string.shortcut_private_field, Toast.LENGTH_LONG).show()
            return
        }
        val selection = field.takeUnless { it.isShowingHintText }?.text?.toString()
            ?.let { Selection.of(it, field.textSelectionStart, field.textSelectionEnd) }
        if (selection == null || selection.text.isEmpty()) {
            Toast.makeText(this, R.string.shortcut_no_text, Toast.LENGTH_LONG).show()
            return
        }

        // Back closes the card as a tap outside it does; [close] is set just below.
        var onBack: () -> Unit = {}
        val window = OverlayWindow(this, onBack = { onBack() })
        val check = ViewModelProvider(window, CheckViewModel.factory(App.of(this)))[CheckViewModel::class.java]
        var language by mutableStateOf<Language?>(null)
        // The app opens in a separate task. Hide this overlay until it returns, keeping
        // the same view model, Meaning page, source field and accepted changes.
        val backFromApp: () -> Unit = {
            window.hidden = false
            check.recheckIfStale()
        }
        val openApp: () -> Unit = {
            val returnToken = PopupReturn.await(backFromApp)
            window.hidden = true
            try {
                startActivity(PopupAppActivity.intent(this, returnToken))
            } catch (_: RuntimeException) {
                PopupReturn.left(returnToken)
                Toast.makeText(this, R.string.open_linguize_failed, Toast.LENGTH_SHORT).show()
            }
        }

        var closed = false
        // Ends this card, once: Back can arrive both as a key and as a back callback.
        fun end(applied: () -> Boolean) {
            if (closed) return
            closed = true
            PopupReturn.cancel(backFromApp)
            // Restore the source app's window before requesting a replacement.
            // Keep the view model alive until the write result is reported to History.
            window.remove { check.dismiss(applied = applied()) }
            if (card?.window === window) card = null
        }
        val close: () -> Unit = {
            // Only a successful replacement is applied. A failed write never copies
            // text or records a Copy decision.
            end { check.workingText?.let { writeBack(field, selection, it) } == true }
        }
        val menus = MenuState()
        onBack = { if (!menus.dismiss()) close() }

        val actions = CardActions.of(
            check, context = this, onClose = close, onOpenSettings = null,
            onCopyClose = { end { false } },
        )
        try {
            window.show {
                AppTheme(menuState = menus) {
                    check.state?.let { state ->
                        FloatingCard(state, actions,
                            onDismiss = close,
                            onOpenApp = openApp,
                        ) {
                            // Another language checks again on the same card: one history session for it.
                            LanguagePicker(language, onSelect = {
                                language = it
                                check.changeLanguage(it)
                            }, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        } catch (failure: RuntimeException) {
            // No model request starts before display setup succeeds. Clear any partial setup.
            window.remove { check.dismiss(applied = false) }
            throw failure
        }
        card = Card(window, field, discard = { end { false } })
        check.check(selection.text, language, Origin.Button, field.packageName?.toString())
    }

    /**
     * Whether this card, hidden behind the app, was left there: the button is now tapped for
     * a field in another app, or the card has waited longer than anyone comes back for.
     * Linguize's own fields don't count, so the card still comes back from there.
     */
    private fun Card.leftBehind(focused: AccessibilityNodeInfo?): Boolean {
        val since = window.hiddenSince ?: return false
        if (SystemClock.elapsedRealtime() - since > MAX_HIDDEN_MILLIS) return true
        if (focused == null || focused.packageName?.toString() == packageName) return false
        return focused != field
    }

    /**
     * Puts the text, with the accepted changes, back in place of the selection. If the field
     * changed or went away while the card was open, it isn't overwritten. Accept never
     * writes to the clipboard: copying is the card's separate Copy action.
     * Returns true only when the app accepted the replacement.
     */
    private fun writeBack(field: AccessibilityNodeInfo, selection: Selection, replacement: String): Boolean {
        val unchanged = field.refresh() && field.text?.toString() == selection.full
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, selection.with(replacement))
        }
        if (unchanged && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            val cursor = selection.cursorAfter(replacement)
            val cursorAt = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorAt)
            return true
        }
        Toast.makeText(this, if (unchanged) R.string.shortcut_replace_failed else R.string.shortcut_field_changed, Toast.LENGTH_LONG).show()
        return false
    }

    private companion object {
        /** How long a card hidden behind the app waits to be brought back. */
        const val MAX_HIDDEN_MILLIS = 15 * 60_000L
    }
}
