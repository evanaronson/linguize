package com.evanaronson.linguize.menu

import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The popup card's first look comes with no keyboard (docs/lab/type-to-accept.md, "First
 * look"). The writer was usually typing in the app the text came from, and its keyboard may
 * still be up when the card opens over it: it would stay over the card, typing into the app
 * below rather than the card, with the card's field unfocused. The window's
 * [SOFT_INPUT_STATE_ALWAYS_HIDDEN][android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN]
 * should close it, but isn't always honoured for a translucent window over the app that has
 * the keyboard, so the popup also asks for it to close, once, as its window first takes focus.
 * Later, a tap on a card field opens the keyboard for the card as usual.
 */
internal class FirstLookKeyboard {
    private var closed = false

    /** From the activity's onWindowFocusChanged. */
    fun windowFocused(window: Window, hasFocus: Boolean) {
        if (!hasFocus || closed) return
        closed = true
        val view = window.decorView
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
        view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
