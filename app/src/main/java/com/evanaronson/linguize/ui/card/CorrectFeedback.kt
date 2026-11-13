package com.evanaronson.linguize.ui.card

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences

/**
 * Lab, Type to accept: what the writer feels and hears as a typed change matches. The two
 * switches under Type to accept (Vibrate when correct, Sound when correct) decide which; the
 * [output] decides how, and respects the system (touch feedback, the ringer).
 */
internal class CorrectFeedback(
    private val vibrate: () -> Boolean,
    private val sound: () -> Boolean,
    private val output: FeedbackOutput,
) {
    fun correct() {
        if (vibrate()) output.haptic()
        if (sound()) output.sound()
    }
}

/** Where the feedback goes: the device, or a fake in tests. */
interface FeedbackOutput {
    /** A short, light confirmation; nothing when the system's touch feedback is off. */
    fun haptic()

    /** A short, quiet sound; nothing when the ringer is silent or on vibrate. */
    fun sound()
}

/** Replaces the device's feedback, for tests. */
val LocalFeedbackOutput = staticCompositionLocalOf<FeedbackOutput?> { null }

/** The feedback for this card, reading the switches each time it fires. */
@Composable
internal fun rememberCorrectFeedback(): CorrectFeedback {
    val context = LocalContext.current
    val view = LocalView.current
    val override = LocalFeedbackOutput.current
    return remember(context, view, override) {
        val preferences = TypeToAcceptPreferences(context)
        val output = override ?: DeviceFeedback(context.applicationContext, view)
        if (override == null && preferences.sound) CorrectSound.prepare(context.applicationContext)
        CorrectFeedback({ preferences.vibrate }, { preferences.sound }, output)
    }
}

private class DeviceFeedback(private val context: Context, private val view: View) : FeedbackOutput {
    override fun haptic() {
        // Without FLAG_IGNORE_GLOBAL_SETTING, so the system's touch feedback switch is respected.
        if (!view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun sound() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        CorrectSound.play(context)
    }
}

/**
 * The bundled sound (res/raw/lab_type_to_accept_correct.wav, see assets/lab_sounds_SOURCES.md),
 * on the sonification stream at the system's volume for it. Loaded once, ahead of the first
 * match; a match before it has loaded is silent rather than late.
 */
private object CorrectSound {
    private var pool: SoundPool? = null
    private var id = 0
    @Volatile private var loaded = false

    @Synchronized
    fun prepare(context: Context) {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
        created.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        id = runCatching { created.load(context, R.raw.lab_type_to_accept_correct, 1) }.getOrDefault(0)
        pool = created
    }

    fun play(context: Context) {
        prepare(context)
        if (loaded) pool?.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    /** Quiet: it confirms, it doesn't celebrate. */
    private const val VOLUME = 0.5f
}
