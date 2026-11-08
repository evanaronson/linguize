package com.evanaronson.linguize.ui.languagebridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the bridge page shows: always the writer's [text] and the [target] language code. */
sealed interface BridgeState {
    val text: String
    val target: String

    data class Loading(override val text: String, override val target: String) : BridgeState
    data class Done(override val text: String, override val target: String, val result: Bridged) : BridgeState
    data class Failed(override val text: String, override val target: String, val failure: CheckFailure) : BridgeState
}

/**
 * One "Say it in …" request at a time. Opening, switching language and closing all
 * discard a late answer to an earlier request. Nothing is recorded or put back here:
 * hosts copy, use or check [BridgeState.Done]'s text.
 */
class BridgeViewModel(
    /** The writer's profile languages, by ISO code, in settings order. */
    private val languages: suspend () -> List<String>,
    private val bridge: suspend (text: String, target: String, sourceHint: String?) -> Bridged,
) : ViewModel() {
    /** Null while the page is closed. */
    var state by mutableStateOf<BridgeState?>(null)
        private set

    /** Profile languages, once read; until then hosts fall back to the app's languages. */
    var targets by mutableStateOf<List<String>>(emptyList())
        private set

    private var sourceHint: String? = null
    private var generation = 0L
    private var job: Job? = null

    init {
        viewModelScope.launch {
            targets = try {
                languages()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Opens the page for [text] in [target]; [sourceHint] is the language the writer pinned, if any. */
    fun open(text: String, target: String, sourceHint: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        this.sourceHint = sourceHint
        run(trimmed, target)
    }

    /** The same text in another language. */
    fun changeTarget(target: String) {
        val current = state ?: return
        if (current.target == target && current !is BridgeState.Failed) return
        run(current.text, target)
    }

    fun retry() {
        val current = state ?: return
        run(current.text, current.target)
    }

    /** Back from settings: a failed request may now work. */
    fun retryIfFailed() {
        if (state is BridgeState.Failed) retry()
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        state = null
    }

    private fun run(text: String, target: String) {
        generation++
        job?.cancel()
        val requested = generation
        val hint = sourceHint
        state = BridgeState.Loading(text, target)
        job = viewModelScope.launch {
            val shown = try {
                BridgeState.Done(text, target, bridge(text, target, hint))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                BridgeState.Failed(text, target, e)
            } catch (e: Exception) {
                BridgeState.Failed(text, target, CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
            }
            if (requested == generation) state = shown
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BridgeViewModel(
                    // Making the bridge reads settings and keys, so not on the main thread.
                    languages = { withContext(Dispatchers.Default) { app.languageBridge }.targets().map { it.language } },
                    bridge = { text, target, hint ->
                        withContext(Dispatchers.Default) { app.languageBridge }.bridge(text, target, hint)
                    },
                )
            }
        }
    }
}
