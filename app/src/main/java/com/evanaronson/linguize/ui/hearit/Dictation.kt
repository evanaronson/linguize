package com.evanaronson.linguize.ui.hearit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.text.languageDisplayName
import java.util.Locale

/**
 * Speak instead of type: opens the phone's speech recognizer in the chosen language (in
 * the writer's variety) and hands back what it heard, merged into [current] by
 * [mergeDictation]. The recognizer is another app's activity, so Linguize needs no
 * microphone permission and never hears the audio.
 */
@Composable
internal fun DictateButton(
    language: Language?,
    current: String,
    sample: String,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(current)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = dictationResult(result.resultCode, result.data)
        if (heard == null) {
            // Backing out of the recognizer is a choice, not a failure.
            if (result.resultCode != Activity.RESULT_CANCELED || result.data != null) {
                Toast.makeText(context, R.string.lab_hear_it_not_heard, Toast.LENGTH_SHORT).show()
            }
        } else {
            onText(mergeDictation(latest.value, heard, sample))
        }
    }
    val profiles = remember { (context.applicationContext as? App)?.settings?.writingProfiles.orEmpty() }
    // In auto-detect, listen for what the box already looks like, else Spanish.
    val text = current.takeUnless { it.trim() == sample.trim() }.orEmpty()
    val locale = SpeechLocales.resolve(language, text, profiles).first()
    val spokenLanguage = language ?: guessLanguage(text) ?: Language.Spanish
    val prompt = stringResource(R.string.lab_hear_it_speak_prompt, languageDisplayName(spokenLanguage.name))
    val description = stringResource(R.string.lab_hear_it_speak_description)
    LinguizeButton(
        onClick = {
            try {
                launcher.launch(dictationIntent(locale, prompt))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.lab_hear_it_no_recognizer, Toast.LENGTH_SHORT).show()
            }
        },
        kind = ButtonKind.Outlined,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(painterResource(R.drawable.ic_lab_hear_it_mic), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.lab_hear_it_speak))
    }
}

/** A free-form recognition request for one best guess in [locale]. */
internal fun dictationIntent(locale: Locale, prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/** The recognizer's best guess, or null when it heard nothing usable or was cancelled. */
internal fun dictationResult(resultCode: Int, data: Intent?): String? {
    if (resultCode != Activity.RESULT_OK) return null
    return data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
}
