package com.evanaronson.linguize.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A full-screen accessibility overlay showing Compose content above every app.
 * Compose needs a lifecycle, saved state and view models, which a service doesn't
 * have, so the window provides its own: each window starts with fresh view models
 * and clears them when it's removed.
 *
 * While shown, the window takes key focus so Back reaches [onBack] rather than the app
 * below, which would otherwise leave the screen the card's text field is on. The
 * keyboard closes while the card is open, matching the text-selection activity.
 */
internal class OverlayWindow(private val context: Context, private val onBack: () -> Unit) :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val params = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        windowFlags(hidden = false),
        PixelFormat.TRANSLUCENT,
    )
    init {
        params.softInputMode = LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private var root: View? = null
    private var screenAvailable = false
    private val unlockedScreen = UnlockedScreen(context) {
        screenAvailable = it
        updateVisibility()
    }

    /** Back on Android 13 and later, where apps that opt in (as targeting 16 does) get no Back key events. */
    private var backCallback: Any? = null

    init {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show(content: @Composable () -> Unit) {
        unlockedScreen.start()
        val root = BackKeyLayout(context, onBack).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setViewTreeViewModelStoreOwner(this@OverlayWindow)
            addView(ComposeView(context).apply { setContent(content) })
        }
        root.visibility = if (hidden || !screenAvailable) View.INVISIBLE else View.VISIBLE
        params.flags = windowFlags(hidden || !screenAvailable)
        lifecycleRegistry.currentState = if (hidden || !screenAvailable) Lifecycle.State.STARTED else Lifecycle.State.RESUMED
        windowManager.addView(root, params)
        this.root = root
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { onBack() }
            root.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    /** When the window was last hidden, on the elapsed-realtime clock; null while it's shown. */
    var hiddenSince: Long? = null
        private set

    /**
     * Hidden, the window is invisible (to screen readers too), lets touches and keys through
     * to whatever is below, and keeps its content.
     */
    var hidden = false
        set(value) {
            if (field == value) return
            field = value
            hiddenSince = if (value) SystemClock.elapsedRealtime() else null
            unlockedScreen.refresh()
        }

    private fun updateVisibility() {
        val root = root ?: return
        val invisible = hidden || !screenAvailable
        root.visibility = if (invisible) View.INVISIBLE else View.VISIBLE
        params.flags = windowFlags(hidden = invisible)
        windowManager.updateViewLayout(root, params)
        lifecycleRegistry.currentState = if (invisible) Lifecycle.State.STARTED else Lifecycle.State.RESUMED
    }

    /**
     * Detaches the focus-taking overlay before [afterDetach] puts text back into the app.
     * Its view models stay alive until that callback has reported the actual outcome.
     */
    fun remove(afterDetach: () -> Unit = {}) {
        unlockedScreen.stop()
        root?.let { root ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (backCallback as? OnBackInvokedCallback)?.let { root.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
            }
            windowManager.removeViewImmediate(root)
        }
        root = null
        backCallback = null
        try {
            afterDetach()
        } finally {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

/**
 * Shown, the window takes focus for Back and hides the source keyboard, just like the
 * text-selection activity. Hidden, it lets all touches and keys pass through and cannot
 * become an input-method target.
 */
internal fun windowFlags(hidden: Boolean): Int =
    if (hidden) {
        LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE
    } else {
        LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

/** The window's root: answers the Back key itself, on versions and apps that still send it as a key. */
private class BackKeyLayout(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
        return true
    }
}

