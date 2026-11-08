package com.evanaronson.linguize.ui.hearit

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Why a control could not play. */
internal sealed interface SpeechProblem {
    /** No text-to-speech engine could be bound. */
    data object NoEngine : SpeechProblem

    /** The engine has no voice for [locale]; [downloadable] when it only needs its data installed. */
    data class MissingVoice(val locale: Locale, val downloadable: Boolean) : SpeechProblem

    /** The engine took the text but failed while speaking it. */
    data object Failed : SpeechProblem
}

/** Whether Hear it plays slowly; kept across cards. */
internal interface SpeedStore {
    var slow: Boolean
}

/** Lab-local preference file, separate from the app's own settings. */
internal class PrefsSpeedStore(context: Context) : SpeedStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override var slow: Boolean
        get() = prefs.getBoolean(SLOW, false)
        set(value) = prefs.edit().putBoolean(SLOW, value).apply()

    companion object {
        const val PREFS = "lab_hear-it"
        private const val SLOW = "slow"
    }
}

internal class MemorySpeedStore(override var slow: Boolean = false) : SpeedStore

/**
 * Plays one thing at a time for one card. Each control names itself with a key ("text",
 * "meaning", "edit:3"): pressing the playing control stops it, pressing another switches.
 *
 * The engine is bound on first use, not when the card opens, and is shut down by [shutdown]
 * (the card leaving the screen) or after a problem, so a voice installed meanwhile in the
 * system settings is found on the next try. Main thread only.
 */
internal class HearItController(
    private val engines: SpeechEngineFactory,
    private val speed: SpeedStore,
) {
    /** The control whose text is playing or about to, or null. */
    var playing by mutableStateOf<String?>(null)
        private set

    /** True while the engine is still starting for [playing]. */
    var preparing by mutableStateOf(false)
        private set

    /** The latest problem, and the control it belongs to. */
    var problem by mutableStateOf<Pair<String, SpeechProblem>?>(null)
        private set

    var slow by mutableStateOf(speed.slow)
        private set

    private var engine: SpeechEngine? = null
    private var ready = false
    private var pending: Request? = null
    private var utterance: String? = null
    private var counter = 0

    private class Request(val key: String, val text: String, val locales: List<Locale>)

    /** Plays [text] for control [key], or stops it when that control is already playing. */
    fun toggle(key: String, text: String, locales: List<Locale>) {
        if (playing == key) stop() else play(key, text, locales)
    }

    fun play(key: String, text: String, locales: List<Locale>) {
        if (text.isBlank() || locales.isEmpty()) return
        engine?.stop()
        utterance = null
        problem = null
        playing = key
        val request = Request(key, text, locales)
        val current = engine
        if (current != null && ready) {
            speakNow(current, request)
        } else {
            pending = request
            preparing = true
            if (current == null) engine = engines(::onReady, ::onUtterance)
        }
    }

    /** Stops whatever is playing. */
    fun stop() {
        pending = null
        utterance = null
        preparing = false
        playing = null
        engine?.stop()
    }

    /** Stops only when [key] is the control playing: that control left the screen. */
    fun stopIf(key: String) {
        if (playing == key) stop()
    }

    fun changeSpeed(slow: Boolean) {
        if (this.slow == slow) return
        this.slow = slow
        speed.slow = slow
    }

    fun clearProblem() {
        problem = null
    }

    /** The card is gone: releases the engine. A later [play] binds a new one. */
    fun shutdown() {
        stop()
        release()
    }

    private fun release() {
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun onReady(success: Boolean) {
        val request = pending
        pending = null
        preparing = false
        if (!success) {
            release()
            if (request != null) fail(request.key, SpeechProblem.NoEngine)
            return
        }
        ready = true
        val current = engine
        if (request != null && current != null) speakNow(current, request)
    }

    private fun speakNow(engine: SpeechEngine, request: Request) {
        val voices = request.locales.map { it to engine.voice(it) }
        val locale = voices.firstOrNull { it.second == Voice.Available }?.first
        if (locale == null) {
            val downloadable = voices.any { it.second == Voice.MissingData }
            // Rebind next time, so a voice installed from settings is seen.
            release()
            fail(request.key, SpeechProblem.MissingVoice(request.locales.first(), downloadable))
            return
        }
        val id = "hear-${++counter}"
        utterance = id
        if (!engine.speak(request.text, locale, if (slow) SLOW_RATE else NORMAL_RATE, id)) {
            utterance = null
            release()
            fail(request.key, SpeechProblem.Failed)
        }
    }

    private fun onUtterance(id: String, event: Utterance) {
        if (id != utterance) return
        utterance = null
        val key = playing
        playing = null
        if (event == Utterance.Failed && key != null) problem = key to SpeechProblem.Failed
    }

    private fun fail(key: String, reason: SpeechProblem) {
        playing = null
        preparing = false
        problem = key to reason
    }

    companion object {
        const val NORMAL_RATE = 1.0f
        const val SLOW_RATE = 0.6f
    }
}
