package com.evanaronson.linguize.ui.photo

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
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.lab.ImageInput
import com.evanaronson.linguize.llm.lab.PhotoReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PhotoState {
    /** No photo yet, or the one shown before the process ended is gone. */
    data object Idle : PhotoState

    /** Opening and shrinking the image on the phone. */
    data object Preparing : PhotoState

    /** Sent; waiting for the model. */
    data object Reading : PhotoState
    data class Done(val reading: PhotoReading) : PhotoState
    data class Failed(val failure: CheckFailure) : PhotoState

    /** The image couldn't be opened or decoded; nothing was sent. */
    data object CantOpen : PhotoState

    /** Shared images are limited per minute, like the selection menu; nothing was sent. */
    data object TooMany : PhotoState
}

/**
 * One photo at a time. The prepared image is held in memory only, so Retry and returning
 * from Settings send the same image without opening it again. It is dropped with the view
 * model, or when another photo replaces it.
 */
class PhotoViewModel(
    private val context: suspend () -> UnderstandContext,
    private val explain: suspend (ImageInput, UnderstandContext) -> PhotoReading,
) : ViewModel() {
    var state by mutableStateOf<PhotoState>(PhotoState.Idle)
        private set
    private var image: ImageInput? = null
    private var ranWith: UnderstandContext? = null
    private var generation = 0L
    private var job: Job? = null

    /** Opens the image with [load] (off the main thread) and sends it. */
    fun understand(load: suspend () -> ImageInput) {
        cancel()
        image = null
        val requested = generation
        state = PhotoState.Preparing
        job = viewModelScope.launch {
            val prepared = try {
                withContext(Dispatchers.Default) { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (requested != generation) return@launch
            if (prepared == null) {
                state = PhotoState.CantOpen
                return@launch
            }
            image = prepared
            send(prepared, requested)
        }
    }

    /** A shared image arrived over the limit; nothing is opened or sent. */
    fun limited() {
        cancel()
        image = null
        state = PhotoState.TooMany
    }

    fun retry() {
        val prepared = image ?: return
        cancel()
        val requested = generation
        job = viewModelScope.launch { send(prepared, requested) }
    }

    /** Returning from Settings: a failure, or a result read with other settings, is read again. */
    fun recheckIfStale() {
        if (image == null || state is PhotoState.Preparing) return
        val requested = generation
        viewModelScope.launch {
            val now = runCatching { context() }.getOrNull()
            if (requested != generation) return@launch
            if (state is PhotoState.Failed || now != ranWith) retry()
        }
    }

    private suspend fun send(prepared: ImageInput, requested: Long) {
        state = PhotoState.Reading
        ranWith = null
        val shown = try {
            val used = context()
            if (requested != generation) return
            ranWith = used
            PhotoState.Done(explain(prepared, used))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CheckFailure) {
            PhotoState.Failed(e)
        } catch (_: IllegalArgumentException) {
            PhotoState.CantOpen
        } catch (e: Exception) {
            PhotoState.Failed(CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
        }
        if (requested == generation) state = shown
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        cancel()
        image = null
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PhotoViewModel(
                    context = { withContext(Dispatchers.Default) { app.photoUnderstander }.context() },
                    explain = { image, used -> app.photoUnderstander.understand(image, used) },
                )
            }
        }
    }
}
