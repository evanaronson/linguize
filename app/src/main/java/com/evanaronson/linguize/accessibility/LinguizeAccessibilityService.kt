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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.popup.PopupCard
import com.evanaronson.linguize.ui.popup.PopupExternalActivity
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.home.SettingsReturn
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * Checks text in apps whose selection menu doesn't show Linguize, such as
 * Telegram. Tapping the accessibility button reads the focused text field (the
 * selection, or all of it), floats the result card over the app, and writes the
 * accepted changes back into the field when the card closes. Nothing is read
 * except when the button is tapped.
 */
class LinguizeAccessibilityService : AccessibilityService() {
    /** The card on screen, or waiting hidden behind settings; null when there's none. */
    private var card: Card? = null

    /**
     * An open card: its [window], the [field] it will write back to, and [discard], which
     * closes it without writing anything back.
     */
    private class Card(val window: OverlayWindow, val field: AccessibilityNodeInfo, val discard: () -> Unit)

    override fun onServiceConnected() {
        trace("Connecting shortcut")
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) = tapped()

                    override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                        App.of(this@LinguizeAccessibilityService).shortcutTestAvailable = available
                    }
                },
            )
            App.of(this).shortcutTestAvailable = accessibilityButtonController.isAccessibilityButtonAvailable
            trace("Service connected")
        } catch (failure: RuntimeException) {
            trace("Shortcut registration failed (" + failure.javaClass.simpleName + ")")
        }
    }

    private fun trace(stage: String) {
        App.of(this).shortcutTestStage = stage
    }

    private fun tapped() {
        val app = App.of(this)
        app.shortcutTestTaps++
        trace("Tap received")
        try {
            open()
        } catch (failure: RuntimeException) {
            // The exception's message can contain app data. Keep only its class and stage.
            val failedStage = app.shortcutTestStage
            try {
                card?.discard()
            } catch (_: RuntimeException) {
                // Preserve the failure that brought us here, even if cleanup also fails.
            }
            trace("Failed at " + failedStage + " (" + failure.javaClass.simpleName + ")")
            Toast.makeText(this, "Couldn't open the check. Open Linguize settings for the shortcut test.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // Closed as nothing applied, and no longer waiting for Settings.
        card?.discard()
        card = null
        super.onDestroy()
    }

    private fun open() {
        trace("Finding input focus")
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        card?.let { current ->
            // Tapping the button while the card waits behind settings brings it back, unless
            // it was left there: it waited too long, or the tap is for another app's field.
            if (!current.window.hidden || !current.leftBehind(focused)) {
                current.window.hidden = false
                trace("Existing card shown")
                return
            }
            current.discard()
        }

        // Some editors expose Set text on the input-focused node without the editable flag.
        // Accept only that same focused node; never guess at an unfocused field.
        val supportsSetText = focused?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        val field = focused?.takeIf { it.isEditable || supportsSetText }
        if (field == null) {
            trace(if (focused == null) "No input focus" else
                "Focused item is not editable · write action: no · selection action: " +
                    (if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) "yes" else "no") +
                    " · input focused: " + (if (focused.isFocused) "yes" else "no") +
                    " · children: " + focused.childCount)
            Toast.makeText(this, "No text to check. Tap the field you wrote in, then try again.", Toast.LENGTH_LONG).show()
            return
        }
        trace("Checking field metadata")
        if (field.isPassword || PrivateField.matches(field.inputType, field.hintText, field.viewIdResourceName)) {
            trace("Field looks private")
            Toast.makeText(this, "This field looks private, so Linguize didn't read it.", Toast.LENGTH_LONG).show()
            return
        }
        trace("Reading eligible field")
        val selection = field.takeUnless { it.isShowingHintText }?.text?.toString()
            ?.let { Selection.of(it, field.textSelectionStart, field.textSelectionEnd) }
        if (selection == null || selection.text.isEmpty()) {
            trace(if (field.isShowingHintText) "Field shows a hint" else "No readable text")
            Toast.makeText(this, "No text to check. Tap the field you wrote in, then try again.", Toast.LENGTH_LONG).show()
            return
        }

        // Back closes the card as a tap outside it does; [close] is set just below.
        trace("Preparing card")
        var onBack: () -> Unit = {}
        val window = OverlayWindow(this, onBack = { onBack() })
        val check = ViewModelProvider(window, CheckViewModel.factory(App.of(this)))[CheckViewModel::class.java]
        var language by mutableStateOf<Language?>(null)
        var settingsOpen by mutableStateOf(false)
        val settings = ViewModelProvider(window, SettingsViewModel.factory(App.of(this)))[SettingsViewModel::class.java]

        // Settings opens with the card hidden behind it (an overlay would cover it). Leaving
        // settings brings the card back, checked again if it failed or the settings changed.
        val backFromSettings: () -> Unit = {
            settingsOpen = false
            window.editing = false
            check.recheckIfStale()
        }
        val backFromApp: () -> Unit = {
            settings.refresh()
            window.hidden = false
            check.recheckIfStale()
        }
        val openSettings: () -> Unit = {
            settingsOpen = true
        }
        val openHome: () -> Unit = {
            SettingsReturn.await(backFromApp)
            window.hidden = true
            startActivity(PopupExternalActivity.intent(this, HomeActivity.homeIntent(this)))
        }

        var closed = false
        // Ends this card, once: Back can arrive both as a key and as a back callback.
        fun end(applied: () -> Boolean) {
            if (closed) return
            closed = true
            SettingsReturn.cancel(backFromApp)
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
        onBack = { if (settingsOpen) backFromSettings() else close() }

        val actions = CardActions.of(
            check, context = this, onClose = close, onOpenSettings = openSettings,
            onCopyClose = { end { false } },
        )
        trace("Showing card")
        try {
            window.show {
                AppTheme {
                    check.state?.let { state ->
                        // At the top, clear of the keyboard that's open for the field.
                        PopupCard(state, actions, settings, settingsOpen,
                            onBackFromSettings = backFromSettings,
                            onDismiss = close,
                            onOpenHome = openHome,
                            onOpenExternal = { external ->
                                SettingsReturn.await(backFromApp)
                                window.hidden = true
                                startActivity(PopupExternalActivity.intent(this, external))
                            },
                            onInputModeChanged = { window.editing = it },
                            alignment = Alignment.TopCenter, topPadding = 32.dp) {
                            // Another language checks again on the same card: one history session for it.
                            LanguagePicker(language, onSelect = {
                                language = it
                                check.changeLanguage(it)
                            })
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
        trace("Card shown; starting check")
        check.check(selection.text, language, Origin.Button, field.packageName?.toString())
        trace("Check started")
    }

    /**
     * Whether this card, hidden behind settings, was left there: the button is now tapped for
     * a field in another app, or the card has waited longer than anyone comes back for.
     * Linguize's own fields (settings') don't count, so the card still comes back from there.
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
        val why = if (unchanged) "Couldn't put the text back" else "The field changed or is gone"
        Toast.makeText(this, "$why. Reopen the check and use Copy.", Toast.LENGTH_LONG).show()
        return false
    }

    private companion object {
        /** How long a card hidden behind settings waits to be brought back. */
        const val MAX_HIDDEN_MILLIS = 15 * 60_000L
    }
}
