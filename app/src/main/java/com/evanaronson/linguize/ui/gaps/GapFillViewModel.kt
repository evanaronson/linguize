package com.evanaronson.linguize.ui.gaps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.lab.GapFill
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GapState {
    val text: String

    data class Loading(override val text: String) : GapState
    data class Done(override val text: String, val result: GapFill) : GapState
    data class Failed(override val text: String, val failure: CheckFailure) : GapState
}

/**
 * One Fill the gaps request and the writer's choices among its alternatives. Nothing is
 * saved: hosts decide whether the result is used, checked, copied or put back. For a popup,
 * [replacement] says what goes back to the source app when the card closes.
 */
class GapFillViewModel(
    private val fill: suspend (text: String, language: Language?) -> GapFill,
) : ViewModel() {
    /** Null when no fill is open. */
    var state by mutableStateOf<GapState?>(null)
        private set

    /** The language the latest fill was asked in; null to detect it. */
    var language by mutableStateOf<Language?>(null)
        private set

    /** Home's Fill the gaps box, kept across navigation; Try it can hand text to it. */
    var draft by mutableStateOf<String?>(null)

    /** A popup's fill the writer chose to put back as it is. */
    var used: String? = null
        private set

    /** A popup's fill the writer chose to check: the base text for the new check. */
    var checked: String? = null
        private set

    private var job: Job? = null
    private var generation = 0L

    /** The result text with the writer's chosen alternatives; null unless a fill is done. */
    val result: String? get() = (state as? GapState.Done)?.result?.text

    fun start(text: String, language: Language?) {
        val snapshot = text.trim()
        if (snapshot.isEmpty()) return
        cancel()
        this.language = language
        val requested = generation
        state = GapState.Loading(snapshot)
        job = viewModelScope.launch {
            val shown = try {
                GapState.Done(snapshot, fill(snapshot, language))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                GapState.Failed(snapshot, e)
            } catch (e: Exception) {
                GapState.Failed(snapshot, CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
            }
            if (requested == generation) state = shown
        }
    }

    /** The same text again, in the same language. */
    fun retry() {
        state?.let { start(it.text, language) }
    }

    /** Back from settings: a failed fill (no key, say) runs again. */
    fun retryIfFailed() {
        if (state is GapState.Failed) retry()
    }

    /** Another language for the same text; null to detect it. */
    fun changeLanguage(language: Language?) {
        val current = state ?: return
        if (language != this.language) start(current.text, language)
    }

    /** Uses option [option] of fill [index] in the result. */
    fun choose(index: Int, option: Int) {
        val done = state as? GapState.Done ?: return
        state = done.copy(result = done.result.choose(index, option))
    }

    /** A popup puts the result back as it is; returns it, or null when there is none. */
    fun use(): String? = result?.also { used = it }

    /** The writer will check the result; returns it for the host's check, or null. */
    fun check(): String? = result?.also {
        checked = it
        close()
    }

    /**
     * What a popup puts back when it closes: a fill used as it is, else the check's
     * accepted changes ([checkWorking]), else a checked fill with nothing accepted.
     */
    fun replacement(checkWorking: String?): String? = used ?: checkWorking ?: checked

    /**
     * The text a popup's card is about, to fill: the check's accepted changes, else a fill
     * that went to the check, else the [selected] text.
     */
    fun source(selected: String, checkWorking: String?): String = checkWorking ?: checked ?: selected

    /** Whether the check's own accepted changes are what went back, for its history. */
    fun checkApplied(handedBack: Boolean, checkWorking: String?): Boolean =
        handedBack && used == null && checkWorking != null

    /** Back to the check (or Home's box), dropping the fill. */
    fun close() {
        cancel()
        state = null
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GapFillViewModel(fill = { text, language ->
                    val filler = withContext(Dispatchers.Default) { app.gapFiller }
                    filler.fill(text, language, filler.context())
                })
            }
        }
    }
}
