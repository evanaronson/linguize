package com.evanaronson.linguize.ui.understand

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.UnderstandContext
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.ClipboardText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UnderstandState {
    data object ReadingClipboard : UnderstandState
    data class Message(val unavailable: Boolean) : UnderstandState
    data class Loading(val text: String) : UnderstandState
    data class Done(val text: String, val result: Understood) : UnderstandState
    data class Failed(val text: String, val failure: CheckFailure) : UnderstandState
}

/** No history or write-back hooks. Retries always use the captured input. */
class UnderstandViewModel(
    private val context: suspend () -> UnderstandContext,
    private val explain: suspend (String, UnderstandContext) -> Understood,
) : ViewModel() {
    var state by mutableStateOf<UnderstandState>(UnderstandState.ReadingClipboard)
        private set
    private var text: String? = null
    private var generation = 0L
    private var job: Job? = null
    private var staleCheck: Job? = null
    private var ranWith: UnderstandContext? = null

    fun readingClipboard() {
        cancel()
        text = null
        state = UnderstandState.ReadingClipboard
    }

    fun captured(input: ClipboardText) {
        cancel()
        text = null
        when (input) {
            is ClipboardText.Text -> {
                text = input.value
                retry()
            }
            ClipboardText.NoText -> state = UnderstandState.Message(unavailable = false)
            ClipboardText.Unavailable -> state = UnderstandState.Message(unavailable = true)
        }
    }

    fun retry() {
        val snapshot = text ?: return
        cancel()
        val requested = generation
        ranWith = null
        state = UnderstandState.Loading(snapshot)
        job = viewModelScope.launch {
            val shown = try {
                val used = context()
                if (requested != generation) return@launch
                ranWith = used
                UnderstandState.Done(snapshot, explain(snapshot, used))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                UnderstandState.Failed(snapshot, e)
            } catch (e: Exception) {
                UnderstandState.Failed(snapshot, CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
            }
            if (requested == generation) state = shown
        }
    }

    /** Returning from Settings refreshes the captured message, including an in-flight request. */
    fun recheckIfStale() {
        if (text == null) return
        val requested = generation
        staleCheck?.cancel()
        staleCheck = viewModelScope.launch {
            val now = try {
                context()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (requested != generation || text == null) return@launch
            if (state is UnderstandState.Failed || now == null || now != ranWith) {
                staleCheck = null
                retry()
            }
        }
    }

    fun cancel() {
        generation++
        staleCheck?.cancel()
        staleCheck = null
        job?.cancel()
        job = null
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UnderstandViewModel(
                    context = { withContext(Dispatchers.Default) { app.understander }.context() },
                    explain = { text, used -> app.understander.understand(text, used) },
                )
            }
        }
    }
}
