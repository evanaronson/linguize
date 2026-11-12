package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.card.FloatingPopup
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.card.UnderstandCard
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.theme.AppTheme
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel

/**
 * The share-sheet target for text (ACTION_SEND, text/plain). Floats a small chooser over the
 * app the text came from: Check it runs the usual check card, Understand it the usual
 * reading card. Shared text can't be put back, so the check offers Copy, not Apply.
 */
class ShareActivity : ComponentActivity() {
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val understand: UnderstandViewModel by viewModels { UnderstandViewModel.factory(App.of(this)) }
    private var job by mutableStateOf(ShareJob.Choose)
    private var language by mutableStateOf<Language?>(null)
    private var returningFromApp = false
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        text = SharedContent.text(intent) ?: run {
            Toast.makeText(this, R.string.lab_share_text_no_text, Toast.LENGTH_SHORT).show()
            return finish()
        }
        returningFromApp = savedInstanceState?.getBoolean(KEY_RETURNING) == true
        job = ShareJob.read(savedInstanceState?.getString(KEY_JOB))
        language = Language.forCode(savedInstanceState?.getString(KEY_LANGUAGE))
        // After the process was gone, the view models are new: start the chosen job again.
        when (job) {
            ShareJob.Check -> if (check.state == null) startCheck()
            ShareJob.Understand -> if (understand.state is UnderstandState.ReadingClipboard) startUnderstand()
            ShareJob.Choose -> Unit
        }

        val actions = CardActions.of(check, context = this, onClose = ::finish, onOpenSettings = ::openSettings, canReplace = false)
        setContent {
            AppTheme {
                com.evanaronson.linguize.ui.components.NavigationBackHandler(enabled = job != ShareJob.Choose, onBack = ::backToChoice)
                val state = check.state
                when {
                    job == ShareJob.Check && state != null -> FloatingCard(state, actions, onDismiss = ::finish, onOpenApp = ::openApp) {
                        LanguagePicker(language, onSelect = { selected ->
                            language = selected
                            check.changeLanguage(selected)
                        }, modifier = Modifier.weight(1f, fill = false))
                    }
                    job == ShareJob.Understand -> FloatingPopup(onDismiss = ::finish, onOpenApp = ::openApp) { modifier ->
                        UnderstandCard(understand.state, onRetry = understand::retry, modifier = modifier, onOpenSettings = ::openSettings)
                    }
                    else -> FloatingPopup(onDismiss = ::finish, onOpenApp = ::openApp) { modifier ->
                        ResultSurface(modifier, footer = {
                            FooterRow(
                                stringResource(R.string.lab_share_text_check_it),
                                stringResource(R.string.lab_share_text_check_it_subtitle),
                                onClick = ::startCheck,
                            )
                            FooterRow(
                                stringResource(R.string.lab_share_text_understand_it),
                                stringResource(R.string.lab_share_text_understand_it_subtitle),
                                onClick = ::startUnderstand,
                            )
                        }) {
                            Text(stringResource(R.string.lab_share_text_shared_text), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            Spacer(Modifier.height(6.dp))
                            Text(text, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    private fun startCheck() {
        job = ShareJob.Check
        // Recorded like a selection-menu check when History is on; nothing goes back to the source app.
        check.check(text, language, Origin.Menu, callingPackage)
    }

    private fun startUnderstand() {
        job = ShareJob.Understand
        understand.captured(ClipboardText.Text(text))
    }

    private fun backToChoice() {
        when (job) {
            ShareJob.Check -> check.dismiss(applied = false)
            ShareJob.Understand -> understand.cancel()
            ShareJob.Choose -> Unit
        }
        job = ShareJob.Choose
    }

    private fun openApp() {
        returningFromApp = true
        startActivity(HomeActivity.homeIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSettings() {
        returningFromApp = true
        startActivity(HomeActivity.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onResume() {
        super.onResume()
        if (returningFromApp) {
            returningFromApp = false
            when (job) {
                ShareJob.Check -> check.recheckIfStale()
                ShareJob.Understand -> understand.recheckIfStale()
                ShareJob.Choose -> Unit
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_JOB, job.token)
        outState.putBoolean(KEY_RETURNING, returningFromApp)
        outState.putString(KEY_LANGUAGE, language?.code)
    }

    /** Closes the session; History learns nothing was applied, since nothing can be. */
    override fun finish() {
        if (job == ShareJob.Check) check.dismiss(applied = false, waitMs = CLOSE_WAIT_MS)
        super.finish()
    }

    private companion object {
        const val KEY_JOB = "job"
        const val KEY_RETURNING = "returningFromApp"
        const val KEY_LANGUAGE = "language"
        const val CLOSE_WAIT_MS = 500L
    }
}

/** Which card the share chooser shows. */
internal enum class ShareJob(val token: String) {
    Choose("choose"),
    Check("check"),
    Understand("understand"),
    ;

    companion object {
        fun read(token: String?): ShareJob = entries.firstOrNull { it.token == token } ?: Choose
    }
}
