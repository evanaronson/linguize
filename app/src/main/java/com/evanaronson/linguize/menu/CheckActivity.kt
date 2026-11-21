package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import android.view.WindowManager.LayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.appvoice.AppVoiceChip
import com.evanaronson.linguize.ui.appvoice.CardVoiceViewModel
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ReplyContextActions
import com.evanaronson.linguize.ui.card.FloatingPopup
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * Opened from the text-selection menu (ACTION_PROCESS_TEXT) through one of the
 * entries in [com.evanaronson.linguize.data.MenuEntry]. Floats the result card over the app the text came
 * from, and hands back the text with any accepted changes when it closes.
 */
class CheckActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val voice: CardVoiceViewModel by viewModels { CardVoiceViewModel.factory(App.of(this)) }
    private var selection: Selection? = null
    private var readOnly = true
    private val canReplace: Boolean get() = !readOnly && callingActivity != null
    private var limited = false
    /** Lab, Leave as written: the writer closed the card with it; see [finish]. */
    private var leftAsWritten = false
    private var returningFromApp = false
    private var popupPosition by mutableStateOf(PopupPosition.Bottom)
    private var language by mutableStateOf<Language?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        popupPosition = App.of(this).settings.popupPosition
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Lab, Consistency: the card hears the keyboard itself (its insets), on every Android
        // version, so Back with the keyboard up closes only the keyboard (KeyboardBackHandler in
        // FloatingPopup), never the card. The card pads itself for the bars and the keyboard.
        // enableEdgeToEdge, not just "decor doesn't fit system windows": it also sets the bars' icon colours for the
        // light or dark theme (and a scrim for three-button navigation), so the navigation buttons
        // stay visible under the keyboard.
        enableEdgeToEdge()
        returningFromApp = savedInstanceState?.getBoolean(KEY_RETURNING) == true
        // Exported aliases are callable by any app. Reject malformed launches before
        // starting a check or allowing an ACTION_PROCESS_TEXT replacement result.
        if (intent.action != Intent.ACTION_PROCESS_TEXT) return finish()
        val selected = try {
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        } catch (_: BadParcelableException) {
            return finish()
        } catch (_: ClassCastException) {
            return finish()
        }
        // Whitespace around the selection isn't checked, and stays where it was.
        val selection = Selection.of(selected).takeIf { it.text.isNotEmpty() } ?: return finish()
        this.selection = selection
        // Which menu entry was tapped decides the language: Catalanize, Castilianize, or Linguize to detect it.
        val entry = App.of(this).menu.entryFor(intent.component)
        language = if (savedInstanceState == null) entry.language else Language.forCode(savedInstanceState.getString(KEY_LANGUAGE))
        // The calling app's voice rule (when Android names the caller) pins the language for Auto Detect.
        voice.attach(check)
        if (check.state == null) language = voice.start(callingPackage, language)

        // Any app can open this entry, so each one gets only a few checks a minute. A card already
        // limited stays limited when it's recreated, without counting again.
        limited = check.state == null &&
            (savedInstanceState?.getBoolean(KEY_LIMITED) == true || !CheckRateLimit.menu.tryAcquire(callingPackage))

        // Start the request before the first frame is drawn.
        if (check.state == null && !limited) check.check(selection.text, language, Origin.Menu, callingPackage)

        val actions = CardActions.of(
            check,
            context = this,
            onClose = ::finish,
            onOpenSettings = null,
            canReplace = canReplace,
            // Lab, Replying to: Paste only. The accessibility service can't read the app behind this window.
            replyContext = ReplyContextActions.of(check, this),
            // Lab, Leave as written: only for text the writer can put back. A read-only selection is
            // usually text they received, and leaving it as written means nothing there.
            onLeaveAsWritten = if (canReplace) ::leaveAsWritten else null,
        )
        setContent {
            AppTheme {
                val state = if (limited) CardState.Failed(Reason.TooMany, getString(R.string.menu_check_not_sent)) else check.state
                state?.let {
                    FloatingPopup(::finish, ::openApp, popupPosition, header = {
                        LanguagePicker(language, onSelect = { selected ->
                            language = selected
                            if (!limited) check.changeLanguage(selected)
                        }, modifier = Modifier.weight(1f, fill = false))
                        AppVoiceChip(voice, onChanged = { if (!limited) check.recheck() }, Modifier.weight(1f, fill = false))
                    }) { modifier ->
                        ResultCard(it, actions, modifier)
                    }
                }
            }
        }
    }

    /** Lab, Leave as written: closes the card, nothing handed back, and saves the check as written. */
    internal fun leaveAsWritten() {
        leftAsWritten = true
        finish()
    }

    /** Open the app separately, keeping this selection's result channel and accepted edits. */
    internal fun openApp() {
        returningFromApp = true
        startActivity(HomeActivity.homeIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onResume() {
        super.onResume()
        popupPosition = App.of(this).settings.popupPosition
        if (returningFromApp) {
            returningFromApp = false
            check.recheckIfStale()
        }
    }

    // Lab, Time spent: the card's review time doesn't count while it's off screen.
    override fun onStart() {
        super.onStart()
        check.timing.visible(true)
    }

    override fun onStop() {
        super.onStop()
        check.timing.visible(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LIMITED, limited)
        outState.putBoolean(KEY_RETURNING, returningFromApp)
        outState.putString(KEY_LANGUAGE, language?.code)
    }

    /**
     * When the card closes through Apply, back or tapping outside, accepted changes go back
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
        val handedBack = if (canReplace && accepted != null && selection != null) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, selection.with(accepted)))
            true
        } else {
            false
        }
        if (leftAsWritten && !handedBack) check.leaveAsWritten(waitMs = CLOSE_WAIT_MS)
        else check.dismiss(applied = handedBack, waitMs = CLOSE_WAIT_MS)
        super.finish()
    }

    private companion object {
        const val KEY_LIMITED = "limited"
        const val KEY_RETURNING = "returningFromApp"
        const val KEY_LANGUAGE = "language"

        /** How long finishing may wait for the session's close to be written. */
        const val CLOSE_WAIT_MS = 500L
    }
}
