package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
import com.evanaronson.linguize.R
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
    /** The card on screen, or waiting hidden during an external detour; null when there's none. */
    private var card: Card? = null

    /**
     * An open card: its [window], the [field] it will write back to, and [discard], which
     * closes it without writing anything back.
     */
    private class Card(val window: OverlayWindow, val field: AccessibilityNodeInfo, val discard: () -> Unit)

    override fun onServiceConnected() {
        trace(R.string.shortcut_stage_connecting)
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
            trace(R.string.shortcut_stage_connected)
        } catch (failure: RuntimeException) {
            trace(R.string.shortcut_stage_registration_failed, failure.javaClass.simpleName)
        }
    }

    private fun trace(stage: Int, vararg args: Any) {
        App.of(this).shortcutTestStage = getString(stage, *args)
    }

    private fun tapped() {
        val app = App.of(this)
        app.shortcutTestTaps++
        trace(R.string.shortcut_stage_tapped)
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
            trace(R.string.shortcut_stage_failed, failedStage, failure.javaClass.simpleName)
            Toast.makeText(this, R.string.shortcut_open_failed, Toast.LENGTH_LONG).show()
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
        trace(R.string.shortcut_stage_finding_focus)
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        card?.let { current ->
            // Tapping the button while the card waits behind settings brings it back, unless
            // it was left there: it waited too long, or the tap is for another app's field.
            if (!current.window.hidden || !current.leftBehind(focused)) {
                current.window.hidden = false
                trace(R.string.shortcut_stage_existing_card)
                return
            }
            current.discard()
        }

        // Some editors expose Set text on the input-focused node without the editable flag.
        // Accept only that same focused node; never guess at an unfocused field.
        val supportsSetText = focused?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        val field = focused?.takeIf { it.isEditable || supportsSetText }
        if (field == null) {
            if (focused == null) {
                trace(R.string.shortcut_stage_no_focus)
            } else {
                trace(R.string.shortcut_stage_not_editable,
                    getString(if (focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) R.string.shortcut_yes else R.string.shortcut_no),
                    getString(if (focused.isFocused) R.string.shortcut_yes else R.string.shortcut_no),
                    focused.childCount)
            }
            Toast.makeText(this, R.string.shortcut_no_text, Toast.LENGTH_LONG).show()
            return
        }
        trace(R.string.shortcut_stage_checking_field)
        if (field.isPassword || PrivateField.matches(field.inputType, field.hintText, field.viewIdResourceName)) {
            trace(R.string.shortcut_stage_private_field)
            Toast.makeText(this, R.string.shortcut_private_field, Toast.LENGTH_LONG).show()
            return
        }
        trace(R.string.shortcut_stage_reading_field)
        val selection = field.takeUnless { it.isShowingHintText }?.text?.toString()
            ?.let { Selection.of(it, field.textSelectionStart, field.textSelectionEnd) }
        if (selection == null || selection.text.isEmpty()) {
            trace(if (field.isShowingHintText) R.string.shortcut_stage_hint else R.string.shortcut_stage_empty)
            Toast.makeText(this, R.string.shortcut_no_text, Toast.LENGTH_LONG).show()
            return
        }

        // Back closes the card as a tap outside it does; [close] is set just below.
        trace(R.string.shortcut_stage_preparing_card)
        var onBack: () -> Unit = {}
        val window = OverlayWindow(this, onBack = { onBack() })
        val check = ViewModelProvider(window, CheckViewModel.factory(App.of(this)))[CheckViewModel::class.java]
        var language by mutableStateOf<Language?>(null)
        var settingsOpen by mutableStateOf(false)
        val settings = ViewModelProvider(window, SettingsViewModel.factory(App.of(this)))[SettingsViewModel::class.java]

        // Embedded settings shares this window and check. External destinations hide the
        // window until their matching gateway returns, retaining the same accepted edits.
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
        val openExternal: (Intent) -> Unit = { external ->
            val returnToken = SettingsReturn.await(backFromApp)
            window.hidden = true
            try {
                startActivity(PopupExternalActivity.intent(this, external, returnToken))
            } catch (_: RuntimeException) {
                SettingsReturn.left(returnToken)
                Toast.makeText(this, R.string.external_app_unavailable, Toast.LENGTH_SHORT).show()
            }
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
        trace(R.string.shortcut_stage_showing_card)
        try {
            window.show {
                AppTheme {
                    check.state?.let { state ->
                        // At the top, clear of the keyboard that's open for the field.
                        PopupCard(state, actions, settings, settingsOpen,
                            onBackFromSettings = backFromSettings,
                            onDismiss = close,
                            onOpenHome = { openExternal(HomeActivity.homeIntent(this)) },
                            onOpenExternal = openExternal,
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
        trace(R.string.shortcut_stage_starting_check)
        check.check(selection.text, language, Origin.Button, field.packageName?.toString())
        trace(R.string.shortcut_stage_check_started)
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
        Toast.makeText(this, if (unchanged) R.string.shortcut_replace_failed else R.string.shortcut_field_changed, Toast.LENGTH_LONG).show()
        return false
    }

    private companion object {
        /** How long a card hidden behind settings waits to be brought back. */
        const val MAX_HIDDEN_MILLIS = 15 * 60_000L
    }
}
