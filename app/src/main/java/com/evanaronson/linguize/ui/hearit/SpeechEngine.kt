package com.evanaronson.linguize.ui.hearit

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Whether the engine can speak a locale. */
internal enum class Voice { Available, MissingData, NotSupported }

/** What an engine reports about one utterance. */
internal enum class Utterance { Done, Failed }

/** The part of Android's text-to-speech the [HearItController] uses, so tests can fake it. */
internal interface SpeechEngine {
    fun voice(locale: Locale): Voice

    /** Queues [text], replacing anything playing; false when the engine refused it. */
    fun speak(text: String, locale: Locale, rate: Float, id: String): Boolean
    fun stop()
    fun shutdown()
}

/**
 * Makes an engine. It calls [onReady] once, with false when no engine could be bound, and
 * [onUtterance] as each utterance ends. Both arrive on the main thread.
 */
internal typealias SpeechEngineFactory =
    (onReady: (Boolean) -> Unit, onUtterance: (id: String, Utterance) -> Unit) -> SpeechEngine

/** [TextToSpeech] with its binder-thread callbacks moved to the main thread. */
internal class AndroidSpeechEngine(
    context: Context,
    onReady: (Boolean) -> Unit,
    private val onUtterance: (String, Utterance) -> Unit,
) : SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var shutDown = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { if (!shutDown) onReady(status == TextToSpeech.SUCCESS) }
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = report(utteranceId, Utterance.Done)
            // Stopping is not a failure: the controller already moved on.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = report(utteranceId, Utterance.Done)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = report(utteranceId, Utterance.Failed)
            override fun onError(utteranceId: String?, errorCode: Int) = report(utteranceId, Utterance.Failed)
        })
    }

    private fun report(id: String?, event: Utterance) {
        if (id != null) main.post { if (!shutDown) onUtterance(id, event) }
    }

    override fun voice(locale: Locale): Voice = when (runCatching { tts.isLanguageAvailable(locale) }.getOrNull()) {
        TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE ->
            Voice.Available
        TextToSpeech.LANG_MISSING_DATA -> Voice.MissingData
        else -> Voice.NotSupported
    }

    override fun speak(text: String, locale: Locale, rate: Float, id: String): Boolean {
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return false
        tts.setSpeechRate(rate)
        val limited = text.take(TextToSpeech.getMaxSpeechInputLength())
        return tts.speak(limited, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        shutDown = true
        main.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
