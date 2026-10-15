package com.evanaronson.linguize.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.readCopiedText
import com.evanaronson.linguize.ui.card.FloatingPopup
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.card.UnderstandCard
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel
import androidx.lifecycle.ViewModelProvider
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
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
    private val retryHandler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null

    private fun cancelRetry() {
        pendingRetry?.let(retryHandler::removeCallbacks)
        pendingRetry = null
    }

    /**
     * An open card: its [window], the [field] it will write back to, and [discard], which
     * closes it without writing anything back.
     */
    private class Card(val window: OverlayWindow, val field: AccessibilityNodeInfo?, val discard: () -> Unit)

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
        cancelRetry()
        openSafely()
    }

    private fun openSafely(retrySource: AccessibilityNodeInfo? = null) {
        try {
            open(retrySource)
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
        cancelRetry()
        // Closed as nothing applied, and no longer waiting for the app.
        card?.discard()
        card = null
        super.onDestroy()
    }

    private fun open(retrySource: AccessibilityNodeInfo? = null) {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) return
        // Chat navigation can replace virtual editors without invalidating Android's
        // cached input focus. Refreshing the old node cannot discover its replacement.
        // Start this explicit tap with a fresh focus/tree query, not the previous chat.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
        if (retrySource != null) {
            val active = rootInActiveWindow
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !getSystemService(PowerManager::class.java).isInteractive ||
                active == null || !FocusedField.sameWindow(active, retrySource)) {
                return
            }
        }
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (retrySource != null && focused != null && !FocusedField.sameWindow(focused, retrySource)) {
            return
        }
        card?.let { current ->
            // Tapping the button while the card waits behind the app brings it back, unless
            // it was left there: it waited too long, or the tap is for another app's field.
            if (!current.window.hidden || !current.leftBehind(focused)) {
                current.window.hidden = false
                return
            }
            current.discard()
        }

        val direct = focused?.let(FocusedField::supportsEditing) == true
        val root = if (direct) null else rootInActiveWindow
        val source = focused ?: root
        val result = FocusedField.resolve(root, focused)
        // ChatGPT can expose a conversation container on the first lookup, then its
        // editor on the next. Repeat the query once, rather than enlarging the scan or
        // reading a candidate from an incomplete tree. Never retry private/ambiguous fields.
        if (result.field == null && result.reason == "Lookup limit reached" &&
            retrySource == null && source != null) {
            pendingRetry = Runnable {
                pendingRetry = null
                openSafely(source)
            }.also { retryHandler.postDelayed(it, 100) }
            return
        }
        var resolved = result.field.takeUnless { result.reason == "Field looks private" }
        // Refresh direct results too: a cached node may still hold the pre-focus text.
        if (resolved != null && (!resolved.refresh() || !resolved.isVisibleToUser || !resolved.isEnabled ||
            !FocusedField.supportsEditing(resolved) || (!direct &&
                (!resolved.isFocused || source == null || !FocusedField.sameWindow(resolved, source) ||
                    rootInActiveWindow?.let { FocusedField.sameWindow(it, source) } != true)))) {
            resolved = null
        }
        val field = resolved?.takeUnless(FocusedField::isPrivate)
        // Read writing only after privacy checks. Hint/empty text falls through to Understand.
        val selection = FocusedField.selection(field)

        // Back closes the card as a tap outside it does; [close] is set just below.
        var onBack: () -> Unit = {}
        val window = OverlayWindow(this, onBack = { onBack() })
        val check = ViewModelProvider(window, CheckViewModel.factory(App.of(this)))[CheckViewModel::class.java]
        val understand = ViewModelProvider(window, UnderstandViewModel.factory(App.of(this)))[UnderstandViewModel::class.java]
        var understanding by mutableStateOf(selection == null)
        var popupPosition by mutableStateOf(App.of(this).settings.popupPosition)
        var language by mutableStateOf<Language?>(null)
        // The app opens in a separate task. Hide this overlay until it returns, keeping
        // the same view model, Meaning page, source field and accepted changes.
        val backFromApp: () -> Unit = {
            popupPosition = App.of(this).settings.popupPosition
            window.hidden = false
            if (understanding) {
                if (understand.state is UnderstandState.Failed) understand.retry()
            } else check.recheckIfStale()
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
            window.remove {
                understand.cancel()
                check.dismiss(applied = if (understanding) false else applied())
            }
            if (card?.window === window) card = null
        }
        val close: () -> Unit = {
            // Only a successful replacement is applied. A failed write never copies
            // text or records a Copy decision.
            end { if (field != null && selection != null) check.workingText?.let { writeBack(field, selection, it) } == true else false }
        }
        val menus = MenuState()
        onBack = { if (!menus.dismiss()) close() }

        val actions = CardActions.of(
            check, context = this, onClose = close, onOpenSettings = null,
        )
        fun captureClipboard() {
            understand.readingClipboard()
            window.whenFocused(
                onReady = { understand.captured(readCopiedText()) },
                onUnavailable = { understand.captured(ClipboardText.Unavailable) },
            )
        }
        fun switchToUnderstand() {
            // Explicitly discard even if a correction answer already arrived.
            check.discard()
            understanding = true
            menus.dismiss()
            captureClipboard()
        }
        try {
            window.show {
                AppTheme(menuState = menus) {
                    FloatingPopup(
                        onDismiss = close,
                        onOpenApp = openApp,
                        position = popupPosition,
                        header = {
                            if (understanding) {
                                Text(stringResource(R.string.understand), Modifier.padding(vertical = 14.dp))
                            } else {
                                LanguagePicker(language, onSelect = {
                                    language = it
                                    check.changeLanguage(it)
                                }, modifier = Modifier.weight(1f, fill = false))
                            }
                        },
                    ) { modifier ->
                        if (understanding) {
                            UnderstandCard(understand.state, onRetry = {
                                if (understand.state is UnderstandState.Message) captureClipboard() else understand.retry()
                            }, modifier = modifier)
                        } else {
                            check.state?.let { state ->
                                ResultCard(state, actions, modifier, leadingContent = {
                                    TextButton(onClick = ::switchToUnderstand) {
                                        Text(stringResource(R.string.understand_copied_text))
                                    }
                                })
                            }
                        }
                    }
                }
            }
        } catch (failure: RuntimeException) {
            window.remove { check.dismiss(applied = false) }
            throw failure
        }
        card = Card(window, field.takeIf { selection != null }, discard = { end { false } })
        if (selection != null) {
            check.check(selection.text, language, Origin.Button, field?.packageName?.toString())
        } else {
            captureClipboard()
        }
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
