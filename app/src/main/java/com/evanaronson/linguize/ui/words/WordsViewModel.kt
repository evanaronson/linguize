package com.evanaronson.linguize.ui.words

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.core.words.WordIndexCache
import com.evanaronson.linguize.core.words.WordSort
import com.evanaronson.linguize.core.words.WordViews
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.data.lab.NotTypedTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class WordsState(
    /** True until the first build finishes. */
    val loading: Boolean = true,
    val index: WordIndex? = null,
    /** The language shown; null picks the most used. */
    val language: String? = null,
    val sort: WordSort = WordSort.MostUsed,
    val query: String = "",
    /** Group by dictionary form, when the lemmatizer knows any morphology. */
    val byLemma: Boolean = false,
) {
    /** The language shown: the chosen one while it has words, otherwise the most used. */
    val shown: LanguageWords?
        get() = index?.let { index -> index.languages.firstOrNull { it.language == language } ?: index.languages.firstOrNull() }
}

/** A CSV ready for the share sheet, or a failed write. */
sealed interface WordsExport {
    data class Ready(val uri: Uri, val language: String?) : WordsExport
    data object Failed : WordsExport
}

/**
 * Your words for Home, History's row, the words page and a word's page. The index is
 * derived from the corpus: rebuilt after every History write (so a deleted check leaves it
 * at once) and whenever a screen asks, from a cache keyed by the corpus fingerprint.
 */
@OptIn(FlowPreview::class)
class WordsViewModel internal constructor(
    private val corpus: Corpus,
    lemmatizer: Lemmatizer,
    revisions: Flow<Long>,
    private val exportCsv: suspend (LanguageWords) -> Uri? = { null },
) : ViewModel() {
    private val cache = WordIndexCache(lemmatizer)
    private val _state = MutableStateFlow(WordsState())
    val state: StateFlow<WordsState> = _state.asStateFlow()

    private val _export = MutableStateFlow<WordsExport?>(null)
    val export: StateFlow<WordsExport?> = _export.asStateFlow()

    private val asked = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(revisions, asked) { revision, ask -> revision to ask }
                .debounce(REBUILD_DELAY_MS)
                .collectLatest { rebuild() }
        }
    }

    /** Reads the corpus again; cheap when nothing changed. */
    fun refresh() {
        asked.update { it + 1 }
    }

    private suspend fun rebuild() {
        val index = withContext(Dispatchers.Default) {
            val utterances = corpus.utterances()
            cache.index(utterances)
        }
        _state.update { it.copy(loading = false, index = index) }
    }

    fun selectLanguage(language: String?) = _state.update { it.copy(language = language) }
    fun sortBy(sort: WordSort) = _state.update { it.copy(sort = sort) }
    fun search(query: String) = _state.update { it.copy(query = query) }
    fun groupByLemma(byLemma: Boolean) = _state.update { it.copy(byLemma = byLemma) }

    /** Writes the shown language's CSV for the share sheet. */
    fun exportCsv() {
        val words = _state.value.shown ?: return
        viewModelScope.launch {
            val uri = runCatching { exportCsv(words) }.getOrNull()
            _export.value = uri?.let { WordsExport.Ready(it, words.language) } ?: WordsExport.Failed
        }
    }

    fun exportHandled() {
        _export.value = null
    }

    companion object {
        const val REBUILD_DELAY_MS = 250L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WordsViewModel(app.corpus, app.lemmatizer, app.history.revisions()) { words -> writeCsv(app, words) }
            }
        }

        /** Into the cache folder the FileProvider shares; the next History export or prune removes it. */
        private suspend fun writeCsv(app: App, words: LanguageWords): Uri = withContext(Dispatchers.IO) {
            val folder = File(app.cacheDir, Exports.FOLDER).apply { mkdirs() }
            folder.listFiles { file -> file.name.startsWith(CSV_PREFIX) }?.forEach { it.delete() }
            val name = "$CSV_PREFIX${words.language ?: "other"}-${LocalDate.now()}-"
            val file = File.createTempFile(name, ".csv", folder)
            file.writeText(WordViews.csv(words), Charsets.UTF_8)
            FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }

        private const val CSV_PREFIX = "linguize-words-"
    }
}

/**
 * Lab: tells Your words that [text] was written by Linguize (gaps filled, said in another
 * language), so a saved check of it isn't counted as the writer's own words.
 */
fun markNotTyped(context: Context, text: String) {
    val app = App.of(context)
    app.appScope.launch { NotTypedTexts(app).mark(text) }
}
