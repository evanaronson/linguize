package com.evanaronson.linguize.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.evanaronson.linguize.App
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.SessionScreen
import com.evanaronson.linguize.ui.history.SessionViewModel
import com.evanaronson.linguize.ui.settings.SettingsScreen
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * The launcher icon's activity: Home (try a check, recent checks), Settings behind the
 * gear, and a past check's page. A card's "Open settings" comes straight to Settings
 * through [settingsIntent], and back from there returns to the card. If the activity was
 * already open, that keeps what it showed (Try it's text, a past check) for next time.
 */
class HomeActivity : ComponentActivity() {
    private val settings: SettingsViewModel by viewModels { SettingsViewModel.factory(App.of(this)) }
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val history: HistoryViewModel by viewModels { HistoryViewModel.factory(App.of(this)) }
    private val session: SessionViewModel by viewModels { SessionViewModel.factory(App.of(this)) }

    private var screen by mutableStateOf<Screen>(Screen.Home)

    /** Settings was opened from a card: back leaves for the card instead of going to Home. */
    private var backToCard = false

    /**
     * What this activity showed before a card opened Settings over it, to show again once
     * back has left for the card. Null when the activity was started just for Settings.
     */
    private var beforeCard: Screen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            show(intent, existing = false)
        } else {
            screen = Screen.restore(savedInstanceState.getString(SCREEN)) ?: Screen.Home
            backToCard = savedInstanceState.getBoolean(BACK_TO_CARD)
            beforeCard = Screen.restore(savedInstanceState.getString(BEFORE_CARD))
            (screen as? Screen.Detail ?: beforeCard as? Screen.Detail)?.let { session.load(it.id) }
        }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    BackHandler(enabled = screen != Screen.Home, onBack = ::back)
                    // Keeps Home's text, card and scroll position while another screen is shown.
                    val saved = rememberSaveableStateHolder()
                    when (val current = screen) {
                        Screen.Home -> saved.SaveableStateProvider(current.saved) {
                            HomeScreen(
                                check,
                                history,
                                historyOn = settings.state?.historyEnabled,
                                onOpenSettings = { screen = Screen.Settings },
                                onOpen = ::open,
                            )
                        }
                        Screen.Settings -> saved.SaveableStateProvider(current.saved) {
                            SettingsScreen(settings, onBack = ::back)
                        }
                        is Screen.Detail -> SessionScreen(session, onBack = ::back)
                    }
                }
            }
        }
    }

    /** A card opened Settings while this activity was already at the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent, existing = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SCREEN, screen.saved)
        outState.putBoolean(BACK_TO_CARD, backToCard)
        outState.putString(BEFORE_CARD, beforeCard?.saved)
    }

    /**
     * Going home or to recents. Links this screen opens say they're not the user leaving.
     * A card waiting behind Settings is told to come back, so Settings is no longer the
     * card's: opened again from the launcher, its back goes to Home as usual.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        backToCard = false
        beforeCard = null
        SettingsReturn.left()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) SettingsReturn.left()
    }

    /** [existing]: the activity was already open, showing [screen], when [intent] came. */
    private fun show(intent: Intent, existing: Boolean) {
        // Reopened from recents, the old intent comes again; the card it was for is long gone.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (intent.getBooleanExtra(EXTRA_SETTINGS, false) && !fromHistory) {
            // A second card's Settings over the first keeps what was shown before either.
            if (!backToCard) beforeCard = if (existing) screen else null
            screen = Screen.Settings
            backToCard = true
        }
    }

    private fun open(id: String) {
        session.load(id)
        screen = Screen.Detail(id)
    }

    private fun back() {
        if (screen != Screen.Settings || !backToCard) {
            if (screen == Screen.Settings) retryAfterSettings()
            screen = Screen.Home
            return
        }
        backToCard = false
        val before = beforeCard ?: return finish()
        // Back to the card's app, as finishing would, but without losing what this activity
        // showed: it's there again the next time Linguize is opened.
        beforeCard = null
        screen = before
        moveTaskToBack(true)
        // Not finishing, and not the user leaving: tell a card waiting behind Settings directly.
        SettingsReturn.left()
    }

    /**
     * Try it's card failed for something Settings fixes (a missing or rejected key, a model
     * that can't be used): back from Settings, it checks again, as the overlay's card does.
     */
    private fun retryAfterSettings() {
        val failed = check.state as? CardState.Failed ?: return
        if (failed.reason in FIXED_IN_SETTINGS) check.recheck()
    }

    companion object {
        /** Failures whose card offers "Open settings" rather than Retry. */
        private val FIXED_IN_SETTINGS = setOf(Reason.NoKey, Reason.BadKey, Reason.BadModel)

        private const val EXTRA_SETTINGS = "com.evanaronson.linguize.extra.SETTINGS"
        private const val SCREEN = "screen"
        private const val BACK_TO_CARD = "backToCard"
        private const val BEFORE_CARD = "beforeCard"

        /** Opens Settings from a card, reusing this activity if it's already on top. */
        fun settingsIntent(context: Context): Intent = Intent(context, HomeActivity::class.java)
            .putExtra(EXTRA_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
