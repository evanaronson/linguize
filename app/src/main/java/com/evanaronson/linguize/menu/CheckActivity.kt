package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.popup.PopupCard
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * Opened from the text-selection menu (ACTION_PROCESS_TEXT) through one of the
 * entries in [com.evanaronson.linguize.data.MenuEntry]. Floats the result card over the app the text came
 * from, and hands back the text with any accepted changes when it closes.
 */
class CheckActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private var selection: Selection? = null
    private var limited = false
    private var returningFromSettings = false
    private var settingsOpen by mutableStateOf(false)
    private val settings: SettingsViewModel by viewModels { SettingsViewModel.factory(App.of(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returningFromSettings = savedInstanceState?.getBoolean(KEY_SETTINGS) == true
        settingsOpen = savedInstanceState?.getBoolean(KEY_SETTINGS_OPEN) == true
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        // Whitespace around the selection isn't checked, and stays where it was.
        val selection = Selection.of(selected).takeIf { it.text.isNotEmpty() } ?: return finish()
        this.selection = selection
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        // Which menu entry was tapped decides the language: Catalanize, Castilianize, or Linguize to detect it.
        val entry = App.of(this).menu.entryFor(intent.component)

        // Any app can open this entry, so each one gets only a few checks a minute. A card already
        // limited stays limited when it's recreated, without counting again.
        limited = check.state == null &&
            (savedInstanceState?.getBoolean(KEY_LIMITED) == true || !CheckRateLimit.menu.tryAcquire(callingPackage))

        // Start the request before the first frame is drawn.
        if (check.state == null && !limited) check.check(selection.text, entry.language, Origin.Menu, callingPackage)

        val actions = CardActions.of(
            check,
            context = this,
            onClose = ::finish,
            onOpenSettings = ::openSettings,
            canReplace = !readOnly,
            onCopyClose = ::finishAfterCopy,
        )
        setContent {
            AppTheme {
                BackHandler(enabled = settingsOpen, onBack = ::backFromSettings)
                val state = if (limited) CardState.Failed(Reason.TooMany, TOO_MANY_DETAIL) else check.state
                state?.let {
                    PopupCard(it, actions, settings, settingsOpen,
                        onBackFromSettings = ::backFromSettings,
                        onDismiss = ::finish,
                        onOpenHome = ::openHome,
                        onOpenExternal = { external ->
                            startActivity(external.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
                        })
                }
            }
        }
    }

    /** Settings and Home are a detour: keep this activity, its result channel and its edits. */
    internal fun openSettings() {
        settingsOpen = true
    }

    private fun backFromSettings() {
        settingsOpen = false
        check.recheckIfStale()
    }

    private fun openHome() {
        returningFromSettings = true
        startActivity(HomeActivity.homeIntent(this))
    }

    override fun onResume() {
        super.onResume()
        if (returningFromSettings) {
            returningFromSettings = false
            check.recheckIfStale()
        }
    }

    /** Copy returns no replacement to ACTION_PROCESS_TEXT's caller. */
    private fun finishAfterCopy() {
        check.dismiss(applied = false, waitMs = CLOSE_WAIT_MS)
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LIMITED, limited)
        outState.putBoolean(KEY_SETTINGS, returningFromSettings)
        outState.putBoolean(KEY_SETTINGS_OPEN, settingsOpen)
    }

    /**
     * When the card closes through Done, back or tapping outside, accepted changes go back
     * to the app. History is told here whether they did, while the check is still open: the
     * view model going away later records nothing as applied.
     *
     * Nothing of this app may be running once the card is gone, and a process with nothing
     * running is the first the system kills; so the close of the session (its outcome and
     * every decision) is written before the activity goes, waiting [CLOSE_WAIT_MS] at
     * most. The write is one short transaction, normally a few milliseconds; the limit
     * only matters when the database is slow to open, and then the write goes on alone.
     */
    override fun finish() {
        val accepted = check.workingText
        val selection = selection
        val handedBack = if (accepted != null && selection != null) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, selection.with(accepted)))
            true
        } else {
            false
        }
        check.dismiss(applied = handedBack, waitMs = CLOSE_WAIT_MS)
        super.finish()
    }

    private companion object {
        const val KEY_LIMITED = "limited"
        const val KEY_SETTINGS = "returningFromSettings"
        const val KEY_SETTINGS_OPEN = "settingsOpen"

        /** How long finishing may wait for the session's close to be written. */
        const val CLOSE_WAIT_MS = 500L
        const val TOO_MANY_DETAIL = "This one wasn't sent to the AI."
    }
}
