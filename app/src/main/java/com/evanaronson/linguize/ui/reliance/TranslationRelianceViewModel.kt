package com.evanaronson.linguize.ui.reliance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.chat.ReplyRead
import com.evanaronson.linguize.core.chat.TranslationReliance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every measured reply, read at [now]; [reads] null until read (or when the read failed). */
data class RelianceState(val reads: List<ReplyRead>? = null, val now: Long = 0L) {
    /** The report for one language (null: every language), once read. */
    fun report(language: String?): TranslationReliance? = reads?.let { TranslationReliance.of(it, now, language = language) }
}

/**
 * Lab, Translation reliance: reads the measured replies from the chat's database. The report is
 * worked out per language when shown, so Progress's language blocks share one read.
 */
class TranslationRelianceViewModel(
    private val reads: suspend () -> List<ReplyRead>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RelianceState())
    val state: StateFlow<RelianceState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                mutableState.value = RelianceState(reads(), clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row keeps its explanation; the page shows nothing measured.
                Log.w(TAG, "Couldn't read translation reliance", e)
                mutableState.value = RelianceState(emptyList(), clock())
            }
        }
    }

    companion object {
        private const val TAG = "LabReliance"

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { TranslationRelianceViewModel({ app.chatStore.replyReads() }) }
        }
    }
}
