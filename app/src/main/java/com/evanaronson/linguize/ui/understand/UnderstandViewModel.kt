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
class UnderstandViewModel(private val explain: suspend (String) -> Understood) : ViewModel() {
    var state by mutableStateOf<UnderstandState>(UnderstandState.ReadingClipboard)
        private set
    private var text: String? = null
    private var generation = 0L
    private var job: Job? = null

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
        state = UnderstandState.Loading(snapshot)
        job = viewModelScope.launch {
            val shown = try {
                UnderstandState.Done(snapshot, explain(snapshot))
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

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UnderstandViewModel { text ->
                    val engine = withContext(Dispatchers.Default) { app.understander }
                    engine.understand(text)
                }
            }
        }
    }
}
