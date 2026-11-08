package com.evanaronson.linguize.ui.hearit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import java.util.Locale

/** One card's player, with the voices for the writer's language and their own. */
internal class HearIt(
    val controller: HearItController,
    /** For the message and its changes. */
    val writer: List<Locale>,
    /** For Meaning, written in the writer's native language. */
    val native: List<Locale>,
)

/** Null outside a live check card, where no Hear it control is shown. */
internal val LocalHearIt = compositionLocalOf<HearIt?> { null }

/**
 * Gives the card's controls one player. The engine is bound on first play, playback
 * stops when the app goes to the background, and the engine is released when the card
 * leaves the screen (dismissed, applied or replaced).
 */
@Composable
internal fun ProvideHearIt(state: CardState?, pinned: Language?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val controller = remember {
        HearItController(
            engines = { ready, utterance -> AndroidSpeechEngine(context, ready, utterance) },
            speed = PrefsSpeedStore(context),
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) { onDispose { controller.shutdown() } }

    val profiles = remember { (context.applicationContext as? App)?.settings?.writingProfiles.orEmpty() }
    val source = when (state) {
        is CardState.Done -> state.sourceText ?: (state.result as? CheckResult.Reviewed)?.revision?.original
        is CardState.Loading -> state.text
        else -> null
    }.orEmpty()
    val writer = remember(pinned, source, profiles) { SpeechLocales.resolve(pinned, source, profiles) }
    val uiLocale = LocalConfiguration.current.locales[0]
    val native = remember(uiLocale) { SpeechLocales.forNative(uiLocale) }
    val hearIt = remember(controller, writer, native) { HearIt(controller, writer, native) }
    CompositionLocalProvider(LocalHearIt provides hearIt, content = content)
}

/** The card's own row: Hear it for the message with accepted changes, and Slow. */
@Composable
internal fun HearItBar(text: String, modifier: Modifier = Modifier) {
    val hearIt = LocalHearIt.current ?: return
    if (text.isBlank()) return
    val controller = hearIt.controller
    val key = TEXT_KEY
    StopWhenGone(controller, key)
    val playing = controller.playing == key
    val label = stringResource(if (playing) R.string.lab_hear_it_stop_text else R.string.lab_hear_it_hear_text)
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LinguizeButton(
                onClick = { controller.toggle(key, text, hearIt.writer) },
                kind = ButtonKind.Outlined,
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_lab_hear_it_stop else R.drawable.ic_lab_hear_it_speaker),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playing) R.string.lab_hear_it_stop else R.string.lab_hear_it_hear))
            }
            SlowToggle(controller)
        }
        Problem(controller, key)
    }
}

@Composable
private fun SlowToggle(controller: HearItController) {
    val description = stringResource(R.string.lab_hear_it_slow_description)
    FilterChip(
        selected = controller.slow,
        onClick = { controller.changeSpeed(!controller.slow) },
        label = { Text(stringResource(R.string.lab_hear_it_slow)) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** A speaker button for one change: plays the corrected words. */
@Composable
internal fun HearItChange(words: String, modifier: Modifier = Modifier) {
    val hearIt = LocalHearIt.current ?: return
    if (words.isBlank()) return
    HearItIcon(
        key = "change:$words",
        text = words,
        locales = hearIt.writer,
        hearLabel = stringResource(R.string.lab_hear_it_hear_change, words),
        stopLabel = stringResource(R.string.lab_hear_it_stop_change, words),
        modifier = modifier,
    )
}

/** A speaker button for Meaning, in the writer's own language. */
@Composable
internal fun HearItMeaning(meaning: String, modifier: Modifier = Modifier) {
    val hearIt = LocalHearIt.current ?: return
    if (meaning.isBlank()) return
    HearItIcon(
        key = MEANING_KEY,
        text = meaning,
        locales = hearIt.native,
        hearLabel = stringResource(R.string.lab_hear_it_hear_meaning),
        stopLabel = stringResource(R.string.lab_hear_it_stop_meaning),
        modifier = modifier,
    )
}

@Composable
private fun HearItIcon(
    key: String,
    text: String,
    locales: List<Locale>,
    hearLabel: String,
    stopLabel: String,
    modifier: Modifier = Modifier,
) {
    val controller = LocalHearIt.current?.controller ?: return
    StopWhenGone(controller, key)
    val playing = controller.playing == key
    Column(modifier) {
        // IconButton keeps a 48dp touch target.
        IconButton(onClick = { controller.toggle(key, text, locales) }) {
            Icon(
                painterResource(if (playing) R.drawable.ic_lab_hear_it_stop else R.drawable.ic_lab_hear_it_speaker),
                contentDescription = if (playing) stopLabel else hearLabel,
                modifier = Modifier.size(20.dp),
            )
        }
        Problem(controller, key)
    }
}

/** A control that leaves the screen (another page, a new check) stops its own playback. */
@Composable
private fun StopWhenGone(controller: HearItController, key: String) {
    val current = rememberUpdatedState(key)
    DisposableEffect(controller, key) { onDispose { controller.stopIf(current.value) } }
}

/** Why this control couldn't play, and the way to voice settings when that helps. */
@Composable
private fun Problem(controller: HearItController, key: String) {
    val (owner, problem) = controller.problem ?: return
    if (owner != key) return
    val context = LocalContext.current
    val uiLocale = LocalConfiguration.current.locales[0]
    val title = when (problem) {
        SpeechProblem.NoEngine -> stringResource(R.string.lab_hear_it_no_engine)
        is SpeechProblem.MissingVoice ->
            stringResource(R.string.lab_hear_it_no_voice, problem.locale.getDisplayName(uiLocale))
        SpeechProblem.Failed -> stringResource(R.string.lab_hear_it_failed)
    }
    val hint = when (problem) {
        SpeechProblem.NoEngine -> stringResource(R.string.lab_hear_it_no_engine_hint)
        is SpeechProblem.MissingVoice -> stringResource(
            if (problem.downloadable) R.string.lab_hear_it_download_voice_hint else R.string.lab_hear_it_add_voice_hint,
        )
        SpeechProblem.Failed -> stringResource(R.string.lab_hear_it_failed_hint)
    }
    Column(Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (problem != SpeechProblem.Failed) {
            LinguizeButton(
                stringResource(R.string.lab_hear_it_open_settings),
                onClick = {
                    controller.clearProblem()
                    openVoiceSettings(context, problem)
                },
                kind = ButtonKind.Text,
            )
        }
    }
}

/**
 * Android's text-to-speech settings, or the engine's voice download for a missing voice.
 * Some phones lack one or the other, so each is tried in turn. Works from the overlay too.
 */
internal fun openVoiceSettings(context: Context, problem: SpeechProblem) {
    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    val settings = Intent(TTS_SETTINGS)
    val order = if (problem is SpeechProblem.MissingVoice && problem.downloadable) listOf(install, settings)
        else listOf(settings, install)
    for (intent in order) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, R.string.lab_hear_it_settings_failed, Toast.LENGTH_SHORT).show()
}

internal const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
private const val TEXT_KEY = "text"
private const val MEANING_KEY = "meaning"
