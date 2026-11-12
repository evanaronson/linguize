package com.evanaronson.linguize.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.PatternsLab
import com.evanaronson.linguize.PatternsSnapshot
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Why an explanation request failed, as the card shows it. */
data class ExplainFailure(val reason: CheckFailure.Reason, val message: CheckFailure.Message?)

data class PatternsState(
    /** True until the first read finishes, and while a retry runs. */
    val loading: Boolean = true,
    /** History couldn't be read; the last good snapshot, if any, stays. */
    val failed: Boolean = false,
    val snapshot: PatternsSnapshot? = null,
    /** A key is saved for [provider]. */
    val canExplain: Boolean = false,
    val provider: Provider? = null,
    val explaining: Boolean = false,
    val explainFailure: ExplainFailure? = null,
    /** Lab, Progress IA: the language the report is for, which the page names; null is every language. */
    val language: String? = null,
)

/**
 * Your patterns for Home's card, the patterns page and a pattern's page. The report is
 * rebuilt from History after every History write (so a deleted check leaves it at once)
 * and whenever the screen asks, never stored.
 */
class PatternsViewModel internal constructor(
    private val lab: PatternsLab,
    revisions: Flow<Long>,
) : ViewModel() {
    private val _state = MutableStateFlow(PatternsState())
    val state: StateFlow<PatternsState> = _state.asStateFlow()

    /** One evidence read and its selected language, captured together before any suspension. */
    private data class Query(val generation: Long, val language: String?)
    private var query = Query(0, null)
    private var rebuildJob: Job? = null
    private var explainJob: Job? = null
    private var explanationGeneration = 0L

    init {
        requestRebuild(null)
        viewModelScope.launch {
            revisions.collect { requestRebuild(query.language) }
        }
    }

    /** Reads History again, and whether a key is saved now (it may have been added in Settings). */
    fun refresh() {
        requestRebuild(query.language)
    }

    /** Applies the page's scope and refreshes it with one request. */
    fun enter(language: String?) = requestRebuild(language)

    /** Lab, Language scope: groups only [language]'s fixes. */
    fun setLanguage(language: String?) {
        if (query.language == language) return
        requestRebuild(language)
    }

    private fun requestRebuild(language: String?) {
        val previous = query
        val captured = Query(previous.generation + 1, language)
        query = captured
        // Invalidate immediately, before the debounce can let an old result finish.
        rebuildJob?.cancel()
        cancelExplanation()
        _state.update {
            it.copy(
                snapshot = it.snapshot.takeIf { previous.language == language },
                loading = true, failed = false, language = language,
            )
        }
        rebuildJob = viewModelScope.launch {
            delay(REBUILD_DELAY_MS)
            if (query == captured) rebuild(captured)
        }
    }

    private fun cancelExplanation() {
        explanationGeneration++
        explainJob?.cancel()
        _state.update { it.copy(explaining = false, explainFailure = null) }
    }

    private suspend fun rebuild(captured: Query) {
        val snapshot = lab.load(captured.language)
        val canExplain = lab.canExplain()
        val provider = try {
            lab.provider()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (query != captured) return
        _state.update {
            it.copy(
                loading = false,
                failed = snapshot == null,
                snapshot = snapshot ?: it.snapshot,
                canExplain = canExplain,
                provider = provider,
            )
        }
    }

    /** Sends the current groups to the chosen service for a short explanation, and saves it. */
    fun explain() {
        if (_state.value.loading || _state.value.failed) return
        val report = _state.value.snapshot?.report ?: return
        if (explainJob?.isActive == true) return
        val captured = query
        val generation = ++explanationGeneration
        fun current() = query == captured && explanationGeneration == generation
        _state.update { it.copy(explaining = true, explainFailure = null) }
        explainJob = viewModelScope.launch {
            try {
                if (!current()) return@launch
                val snapshot = lab.explain(report, captured.language)
                if (current()) _state.update { it.copy(snapshot = snapshot) }
            } catch (e: CheckFailure) {
                if (current()) _state.update { it.copy(explainFailure = ExplainFailure(e.reason, e.userMessage)) }
            } catch (_: IOException) {
                if (current()) _state.update { it.copy(failed = true) }
            } finally {
                if (current()) _state.update { it.copy(explaining = false) }
            }
        }
    }

    fun deleteExplanation() {
        requestRebuild(query.language)
        val captured = query
        val generation = explanationGeneration
        viewModelScope.launch {
            lab.deleteExplanation()
            if (query == captured && explanationGeneration == generation) {
                _state.update { it.copy(snapshot = it.snapshot?.copy(explanation = null, status = null), explainFailure = null) }
            }
        }
    }

    companion object {
        const val REBUILD_DELAY_MS = 250L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { PatternsViewModel(app.patterns, app.history.revisions()) }
        }
    }
}
