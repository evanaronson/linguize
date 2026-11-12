package com.evanaronson.linguize.ui.conjugations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** The languages the grid covers, in the order of the switch. */
val CONJUGATION_LANGUAGES = listOf("es", "ca")

data class ConjugationsState(
    /** True until the first count finishes. */
    val loading: Boolean = true,
    /** The writer's text couldn't be read; the last good reports, if any, stay. */
    val failed: Boolean = false,
    val reports: Map<String, ConjugationReport> = emptyMap(),
    /** The language shown; null until chosen or counted. */
    val language: String? = null,
) {
    val shown: String get() = language ?: defaultLanguage(reports)
    val report: ConjugationReport? get() = reports[shown]
}

/** The language with the most counted text, Spanish when there is none. */
fun defaultLanguage(reports: Map<String, ConjugationReport>): String =
    CONJUGATION_LANGUAGES.maxByOrNull { reports[it]?.utterances ?: 0 }?.takeIf { (reports[it]?.utterances ?: 0) > 0 } ?: "es"

/**
 * Your conjugations. The grid is rebuilt from the corpus every time the screen asks, never
 * stored, so text deleted from a check or a chat leaves it on the next visit.
 */
class ConjugationsViewModel internal constructor(
    private val utterances: suspend () -> List<Utterance>,
    private val morphology: () -> Morphology,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConjugationsState())
    val state: StateFlow<ConjugationsState> = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun refresh() {
        val request = ++generation
        job?.cancel()
        mutable.update { it.copy(loading = it.reports.isEmpty(), failed = false) }
        job = viewModelScope.launch {
            val reports = try {
                withContext(worker) {
                    val all = utterances()
                    currentCoroutineContext().ensureActive()
                    val tables = morphology()
                    val timeZone = zone()
                    CONJUGATION_LANGUAGES.associateWith { ConjugationMap.build(all, it, tables, timeZone) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (request != generation) return@launch
            mutable.update {
                if (reports == null) it.copy(loading = false, failed = true)
                else it.copy(loading = false, failed = false, reports = reports)
            }
        }
    }

    fun select(language: String) {
        mutable.update { it.copy(language = language) }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConjugationsViewModel({ app.corpus.utterances() }, { app.morphology }) }
        }
    }
}
