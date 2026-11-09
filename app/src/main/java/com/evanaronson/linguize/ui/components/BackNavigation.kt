package com.evanaronson.linguize.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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
 * Lab, Consistency: Android's rule for Back. While the keyboard is up, the first Back only
 * closes it; the next Back navigates. [handles] is whether the page has a step to go back to.
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
 */
@Composable
fun KeyboardBackHandler() {
    if (LocalOnBackPressedDispatcherOwner.current == null) return
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BackHandler(enabled = backAction(imeVisible(), handles = false) == BackAction.CloseKeyboard) {
        keyboard?.hide()
        focus.clearFocus()
    }
}
