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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
@OptIn(FlowPreview::class)
class PatternsViewModel internal constructor(
    private val lab: PatternsLab,
    revisions: Flow<Long>,
) : ViewModel() {
    private val _state = MutableStateFlow(PatternsState())
    val state: StateFlow<PatternsState> = _state.asStateFlow()

    private val asked = MutableStateFlow(0)

    /** Lab, Language scope: the language the report is for; null is every language. */
    private val language = MutableStateFlow<String?>(null)
    private var explainJob: Job? = null

    init {
        viewModelScope.launch {
            combine(revisions, asked, language) { revision, ask, language -> Triple(revision, ask, language) }
                .debounce(REBUILD_DELAY_MS)
                .collectLatest { (_, _, language) -> rebuild(language) }
        }
    }

    /** Reads History again, and whether a key is saved now (it may have been added in Settings). */
    fun refresh() {
        asked.update { it + 1 }
    }

    /** Lab, Language scope: groups only [language]'s fixes. */
    fun setLanguage(language: String?) {
        if (this.language.value == language) return
        // The old language's groups mustn't show under the new one while it's read.
        _state.update { it.copy(snapshot = null, loading = true, language = language) }
        this.language.value = language
    }

    private suspend fun rebuild(language: String?) {
        _state.update { it.copy(loading = it.snapshot == null, failed = false) }
        val snapshot = lab.load(language)
        val canExplain = lab.canExplain()
        val provider = runCatching { lab.provider() }.getOrNull()
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
        val report = _state.value.snapshot?.report ?: return
        if (explainJob?.isActive == true) return
        _state.update { it.copy(explaining = true, explainFailure = null) }
        explainJob = viewModelScope.launch {
            try {
                val snapshot = lab.explain(report, language.value)
                _state.update { it.copy(snapshot = snapshot) }
            } catch (e: CheckFailure) {
                _state.update { it.copy(explainFailure = ExplainFailure(e.reason, e.userMessage)) }
            } finally {
                _state.update { it.copy(explaining = false) }
            }
        }
    }

    fun deleteExplanation() {
        viewModelScope.launch {
            lab.deleteExplanation()
            _state.update { it.copy(snapshot = it.snapshot?.copy(explanation = null, status = null), explainFailure = null) }
        }
    }

    companion object {
        const val REBUILD_DELAY_MS = 250L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { PatternsViewModel(app.patterns, app.history.revisions()) }
        }
    }
}
