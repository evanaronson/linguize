package com.evanaronson.linguize.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/** What one press of Back does. */
enum class BackAction {
    /** The keyboard is up: close it, and nothing else. */
    CloseKeyboard,

    /** Go back a step: the page's own handler (close a menu, leave a page). */
    Navigate,

    /** Nothing here handles it; the system does (leaves the app or the popup). */
    System,
}

/**
 * Lab, Consistency: Android's rule for Back, everywhere in the app. While the keyboard is up,
 * the first Back only closes it: it never closes a card, a screen or a dialog, and never
 * navigates. The next Back does what it always did. [handles] is whether the page has a step
 * to go back to.
 */
fun backAction(imeVisible: Boolean, handles: Boolean): BackAction = when {
    imeVisible -> BackAction.CloseKeyboard
    handles -> BackAction.Navigate
    else -> BackAction.System
}

/** The keyboard is showing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun imeVisible(): Boolean = WindowInsets.isImeVisible

/**
 * A navigation [BackHandler] that never takes Back while the keyboard is up, so
 * [KeyboardBackHandler] closes the keyboard first. Every Back handler in the app uses it.
 * Does nothing where there is no back dispatcher.
 */
@Composable
fun NavigationBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current == null) return
    val navigate = backAction(imeVisible(), enabled) == BackAction.Navigate
    BackHandler(enabled = navigate, onBack = onBack)
}

/**
 * While the keyboard is up, Back closes it (and leaves the field), instead of falling
 * through to a page, the activity or the popup. Put once at the root of each window.
 *
 * [fieldCounts]: also treat a focused text field in this window as the keyboard being up. For
 * windows that may not be told about the keyboard (a dialog, whose window doesn't pass the
 * keyboard's insets to its content; the card over other apps, where losing the card to a Back
 * meant for the keyboard loses the writer's accepted changes): there, a field with focus is
 * where the keyboard is, and the first Back leaves it. The app's own screens hear the keyboard
 * itself, so a field left focused after the keyboard was closed some other way doesn't take an
 * extra Back there.
 */
@Composable
fun KeyboardBackHandler(fieldCounts: Boolean = false) {
    if (LocalOnBackPressedDispatcherOwner.current == null) return
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val up = imeVisible() || fieldCounts && FocusedFields.anyIn(LocalView.current)
    BackHandler(enabled = backAction(up, handles = false) == BackAction.CloseKeyboard) {
        keyboard?.hide()
        focus.clearFocus()
    }
}

/**
 * Lab, Consistency: the app's text fields that have focus, by the view that hosts them (one per
 * window). For windows that aren't reliably told when the keyboard is up ([KeyboardBackHandler]'s
 * fieldCounts, and the accessibility overlay's Back): a field with focus is where the keyboard is.
 * Read as state, so a Back handler follows it.
 */
object FocusedFields {
    private val focused = mutableStateMapOf<Any, View>()
    private val watchers = mutableMapOf<View, (Boolean) -> Unit>()

    internal fun set(owner: Any, host: View, isFocused: Boolean) {
        val before = anyIn(host)
        // Written only on a change, so a field reporting its unchanged focus recomposes nothing.
        if (isFocused) {
            if (focused[owner] !== host) focused[owner] = host
        } else if (owner in focused) {
            focused.remove(owner)
        }
        val now = anyIn(host)
        if (now != before) watchers[host]?.invoke(now)
    }

    /**
     * Calls [onChange] as soon as a field hosted by [host] takes focus or the last one leaves it,
     * in the same input event, before the field asks for the keyboard; null stops. For the
     * accessibility overlay, which lets the keyboard show only once a card field has focus.
     */
    fun watch(host: View, onChange: ((focused: Boolean) -> Unit)?) {
        if (onChange == null) watchers.remove(host) else watchers[host] = onChange
    }

    /** Whether one of the app's text fields hosted by [host] has focus. */
    fun anyIn(host: View): Boolean = focused.values.any { it === host }
}

/** Reports this text field's focus to [FocusedFields]. On every text field of the app. */
@Composable
fun Modifier.tracksFieldFocus(): Modifier {
    val host = LocalView.current
    val owner = remember { Any() }
    DisposableEffect(host) { onDispose { FocusedFields.set(owner, host, false) } }
    return onFocusChanged { FocusedFields.set(owner, host, it.isFocused) }
}
