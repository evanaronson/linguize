package com.evanaronson.linguize.menu

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingPopup
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.card.UnderstandCard
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.quicktile.ClipboardJob
import com.evanaronson.linguize.ui.theme.AppTheme
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel

/**
 * Opened by the Quick Settings tile and the launcher shortcuts, for text that was copied
 * rather than selected. Android 10+ lets only the focused app read the clipboard, so this
 * transparent activity waits for window focus, reads it once, then floats the same card as
 * the selection menu. There's no field to put changes back into: accepted changes go back
 * on the clipboard when the card closes.
 *
 * Checks made here are not saved in History ([Origin.Tester]): History holds checks whose
 * changes were put back in another app, and nothing is put back from here.
 */
class ClipboardActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val understand: UnderstandViewModel by viewModels { UnderstandViewModel.factory(App.of(this)) }
    private lateinit var job: ClipboardJob
    internal var step by mutableStateOf<ClipboardStep>(ClipboardStep.Reading)
        private set

    /** The check paused for Understand still has work the writer can return to. */
    private var retainedCheck by mutableStateOf(false)
    private var waitingForFocus = false
    private var returningFromApp = false
    private var popupPosition by mutableStateOf(PopupPosition.Bottom)
    private var language by mutableStateOf<Language?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only this app's own tile and shortcuts start it; refuse anything else.
        job = ClipboardJob.of(intent) ?: return finish()
        popupPosition = App.of(this).settings.popupPosition
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        returningFromApp = savedInstanceState?.getBoolean(KEY_RETURNING) == true
        language = Language.forCode(savedInstanceState?.getString(KEY_LANGUAGE))
        val restored = savedInstanceState?.getString(KEY_TEXT)?.let(Selection::of)?.takeIf { it.text.isNotEmpty() }
        if (restored != null) {
            // Recreated: the clipboard was read already and may have changed since.
            show(when (savedInstanceState.getString(KEY_STEP)) {
                STEP_CHECK -> ClipboardStep.Check(restored)
                STEP_UNDERSTAND -> ClipboardStep.Understand(restored)
                else -> ClipboardStep.Choose(restored)
            })
        } else {
            readWhenFocused()
        }

        val actions = CardActions.of(
            check,
            context = this,
            onClose = ::finish,
            onOpenSettings = ::openSettings,
        )
        setContent {
            AppTheme {
                FloatingPopup(
                    onDismiss = ::finish,
                    onOpenApp = ::openApp,
                    position = popupPosition,
                    header = {
                        if (step is ClipboardStep.Check) {
                            LanguagePicker(language, onSelect = {
                                language = it
                                check.changeLanguage(it)
                            }, modifier = Modifier.weight(1f, fill = false))
                        }
                    },
                ) { modifier ->
                    when (val current = step) {
                        ClipboardStep.Reading -> ReadingClipboardCard(modifier)
                        is ClipboardStep.Message -> ClipboardMessageCard(current.copied, ::readWhenFocused, modifier)
                        is ClipboardStep.Choose -> ChooseClipboardJobCard(
                            current.selection.text,
                            onCheck = { show(ClipboardStep.Check(current.selection)) },
                            onUnderstand = { show(ClipboardStep.Understand(current.selection)) },
                            modifier = modifier,
                        )
                        is ClipboardStep.Check -> check.state?.let { state ->
                            ResultCard(state, actions, modifier, onUnderstandCopiedText = {
                                retainedCheck = check.suspendForUnderstand()
                                show(ClipboardStep.Understand(current.selection))
                            })
                        }
                        is ClipboardStep.Understand -> UnderstandCard(
                            understand.state,
                            onRetry = {
                                if (understand.state is UnderstandState.Message) readWhenFocused() else understand.retry()
                            },
                            modifier = modifier,
                            onOpenSettings = ::openSettings,
                            onBackToCheck = if (retainedCheck) ({
                                understand.cancel()
                                step = ClipboardStep.Check(current.selection)
                                check.recheckIfStale()
                            }) else null,
                        )
                    }
                }
            }
        }
    }

    /** Reads now if this window has focus, or as soon as it gets it. */
    private fun readWhenFocused() {
        step = ClipboardStep.Reading
        if (hasWindowFocus()) read() else waitingForFocus = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForFocus) read()
    }

    private fun read() {
        waitingForFocus = false
        show(ClipboardStep.after(job, readClipboard()))
    }

    /** Moves to [next], starting its check or explanation unless it is already showing. */
    private fun show(next: ClipboardStep) {
        step = next
        when (next) {
            is ClipboardStep.Check -> if (check.state == null) {
                check.check(next.selection.text, language, Origin.Tester)
            }
            is ClipboardStep.Understand -> if (understand.state is UnderstandState.ReadingClipboard) {
                understand.captured(Copied.Text(next.selection).forUnderstand)
            }
            else -> Unit
        }
    }

    /** Settings and the app open on top of the card, in its task, so Back returns to it. */
    private fun openSettings() = openInTask(HomeActivity.settingsIntent(this))

    private fun openApp() = openInTask(HomeActivity.homeIntent(this))

    private fun openInTask(intent: Intent) {
        returningFromApp = true
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            returningFromApp = false
            Toast.makeText(this, R.string.open_linguize_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        popupPosition = App.of(this).settings.popupPosition
        if (returningFromApp) {
            returningFromApp = false
            when (step) {
                is ClipboardStep.Check -> check.recheckIfStale()
                is ClipboardStep.Understand -> understand.recheckIfStale()
                else -> Unit
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RETURNING, returningFromApp)
        outState.putString(KEY_LANGUAGE, language?.code)
        val (name, selection) = when (val current = step) {
            is ClipboardStep.Check -> STEP_CHECK to current.selection
            is ClipboardStep.Understand -> STEP_UNDERSTAND to current.selection
            is ClipboardStep.Choose -> STEP_CHOOSE to current.selection
            else -> return
        }
        outState.putString(KEY_STEP, name)
        outState.putString(KEY_TEXT, selection.full)
    }

    /**
     * When the check closes through Apply, Back or a tap outside, accepted changes replace
     * the copied text on the clipboard, with a confirmation. Understanding changes nothing.
     */
    override fun finish() {
        if (::job.isInitialized) {
            val current = step
            val accepted = check.workingText
            if (current is ClipboardStep.Check && accepted != null) {
                putBackOnClipboard(current.selection.with(accepted))
            }
            understand.cancel()
            check.dismiss(applied = false)
        }
        super.finish()
    }

    private companion object {
        const val KEY_RETURNING = "returningFromApp"
        const val KEY_LANGUAGE = "language"
        const val KEY_STEP = "step"
        const val KEY_TEXT = "text"
        const val STEP_CHECK = "check"
        const val STEP_UNDERSTAND = "understand"
        const val STEP_CHOOSE = "choose"
    }
}
