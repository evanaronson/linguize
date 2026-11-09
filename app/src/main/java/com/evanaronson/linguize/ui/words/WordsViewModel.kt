package com.evanaronson.linguize.ui.words

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.DeckChoice
import com.evanaronson.linguize.ImportOutcome
import com.evanaronson.linguize.DeckLibrary
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.words.DeckCoverage
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.core.words.WordFilter
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.core.words.WordIndexCache
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.core.words.WordSort
import com.evanaronson.linguize.core.words.WordViews
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
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

/** A file picked for import, waiting for a name and a language. */
data class PendingImport(val text: String, val suggestedName: String)

/** The last import's outcome, shown under the decks. */
sealed interface ImportMessage {
    data class Imported(val words: Int) : ImportMessage
    data class Failed(val reason: DeckParse.Reason) : ImportMessage
    data object Unreadable : ImportMessage
    data object TooLarge : ImportMessage
}

data class WordsState(
    /** True until the first build finishes. */
    val loading: Boolean = true,
    val index: WordIndex? = null,
    /** The language chosen; null picks the most used. */
    val language: String? = null,
    /** Languages with words or a deck, most written first. */
    val languages: List<String> = emptyList(),
    /** Null: the default, by rank when there's a deck, otherwise most used. */
    val sort: WordSort? = null,
    val filter: WordFilter = WordFilter.All,
    val query: String = "",
    /** Null: lexemes when morphology is known, otherwise word forms. */
    val level: WordLevel? = null,
    val decks: List<DeckChoice> = emptyList(),
    val deckId: String? = null,
    /** The chosen deck against the shown language, at the shown level; null without a deck. */
    val coverage: DeckCoverage? = null,
    /** The chosen deck couldn't be read. */
    val deckFailed: Boolean = false,
    val imported: List<ImportedDeck> = emptyList(),
    val pendingImport: PendingImport? = null,
    val importMessage: ImportMessage? = null,
) {
    /** The language shown: the chosen one, else the first with words or a deck. */
    val shownLanguage: String? get() = language ?: languages.firstOrNull()

    /** The shown language's words; empty when nothing is written in it yet. */
    val shown: LanguageWords?
        get() = index?.let { index ->
            val code = shownLanguage ?: return@let index.languages.firstOrNull()
            index.language(code) ?: LanguageWords.empty(code)
        }

    /** The level shown: the chosen one when morphology is known, otherwise word forms. */
    val shownLevel: WordLevel
        get() = if (shown?.morphology != true) WordLevel.Forms else level ?: WordLevel.Lexemes

    val shownSort: WordSort
        get() = sort?.takeIf { it != WordSort.Rank || coverage != null } ?: if (coverage != null) WordSort.Rank else WordSort.MostUsed
}

/** A CSV ready for the share sheet, or a failed write. */
sealed interface WordsExport {
    data class Ready(val uri: Uri, val language: String?) : WordsExport
    data object Failed : WordsExport
}

/**
 * Words: everything the writer typed, against a frequency deck, for Progress, the Words page
 * and a word's page. One pass: the corpus is read once into the word index (cached by its
 * fingerprint, rebuilt after every History write and whenever a screen asks), and the chosen
 * deck's coverage is counted from that index, cached by the index fingerprint, deck and level.
 */
@OptIn(FlowPreview::class)
class WordsViewModel internal constructor(
    private val corpus: Corpus,
    private val lemmatizer: Lemmatizer,
    private val decks: DeckLibrary?,
    revisions: Flow<Long>,
    private val exportCsv: suspend (LanguageWords, DeckCoverage?) -> Uri? = { _, _ -> null },
) : ViewModel() {
    private val cache = WordIndexCache(lemmatizer)
    private val _state = MutableStateFlow(WordsState())
    val state: StateFlow<WordsState> = _state.asStateFlow()

    private val _export = MutableStateFlow<WordsExport?>(null)
    val export: StateFlow<WordsExport?> = _export.asStateFlow()

    private val asked = MutableStateFlow(0)
    private var coverageKey: String? = null
    private var restored = false

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
        val index = withContext(Dispatchers.Default) { cache.index(corpus.utterances()) }
        if (!restored && decks != null) {
            restored = true
            val saved = runCatching { decks?.selection() }.getOrNull()
            if (saved != null) {
                _state.update {
                    it.copy(
                        language = it.language ?: saved.language,
                        deckId = it.deckId ?: saved.deckId,
                        level = it.level ?: if (saved.level == VocabLevel.Lemma) WordLevel.Lemmas else null,
                    )
                }
            }
        }
        val deckLanguages = decks?.let { runCatching { it.languages() }.getOrNull() }.orEmpty()
        val written = index.languages.mapNotNull { it.language }
        val languages = (written + deckLanguages).distinct()
        _state.update { it.copy(loading = false, index = index, languages = languages) }
        measure()
    }

    /** Counts the chosen deck against the shown language, unless that's already counted. */
    private suspend fun measure() {
        val state = _state.value
        val words = state.shown ?: return
        val language = words.language
        val choices = if (decks != null && language != null) runCatching { decks?.decks(language) }.getOrNull().orEmpty() else emptyList()
        val choice = choices.firstOrNull { it.id == state.deckId } ?: choices.firstOrNull()
        val level = state.shownLevel
        val key = listOf(state.index?.fingerprint, choice?.id, level == WordLevel.Lemmas, language).joinToString("|")
        if (key == coverageKey && state.coverage?.deck?.id == choice?.id) {
            _state.update { it.copy(decks = choices, deckId = choice?.id) }
            return
        }
        val deck: VocabDeck? = choice?.let { runCatching { decks?.deck(it.id) }.getOrNull() }
        val coverage = deck?.let {
            withContext(Dispatchers.Default) {
                val variant = if (level == WordLevel.Lemmas || !it.hasLexemes) it.byLemma() else it
                DeckCoverage.build(words, variant, lemmatizer)
            }
        }
        coverageKey = key
        val imported = decks?.let { runCatching { it.imported() }.getOrNull() }.orEmpty()
        _state.update {
            it.copy(decks = choices, deckId = choice?.id, coverage = coverage, deckFailed = choice != null && deck == null, imported = imported)
        }
        if (language != null && decks != null) {
            decks.select(VocabSelection(language, choice?.id, if (level == WordLevel.Lemmas) VocabLevel.Lemma else VocabLevel.Lexeme))
        }
    }

    private fun remeasure() {
        viewModelScope.launch { measure() }
    }

    fun selectLanguage(language: String?) {
        if (language == _state.value.language) return
        _state.update { it.copy(language = language, deckId = null, filter = WordFilter.All, query = "") }
        remeasure()
    }

    fun selectDeck(id: String) {
        if (id == _state.value.deckId) return
        _state.update { it.copy(deckId = id) }
        coverageKey = null
        remeasure()
    }

    fun countBy(level: WordLevel) {
        if (level == _state.value.shownLevel) return
        _state.update { it.copy(level = level) }
        remeasure()
    }

    fun sortBy(sort: WordSort) = _state.update { it.copy(sort = sort) }
    fun filter(filter: WordFilter) = _state.update { it.copy(filter = filter) }
    fun search(query: String) = _state.update { it.copy(query = query) }

    /** Writes the shown language's CSV for the share sheet. */
    fun exportCsv() {
        val state = _state.value
        val words = state.shown ?: return
        viewModelScope.launch {
            val uri = runCatching { exportCsv(words, state.coverage) }.getOrNull()
            _export.value = uri?.let { WordsExport.Ready(it, words.language) } ?: WordsExport.Failed
        }
    }

    fun exportHandled() {
        _export.value = null
    }

    /** Reads a picked document; a deck is at most a few megabytes of text. */
    fun pick(resolver: ContentResolver, uri: Uri, fallbackName: String) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
                    if (size != null && size > MAX_BYTES) return@runCatching null
                    val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES + 1) } ?: error("No stream")
                    if (bytes.size > MAX_BYTES) null else decodeDeck(bytes)
                }
            }
            val text = read.getOrNull()
            when {
                read.isFailure -> _state.update { it.copy(importMessage = ImportMessage.Unreadable) }
                text == null -> _state.update { it.copy(importMessage = ImportMessage.TooLarge) }
                else -> {
                    val name = withContext(Dispatchers.IO) { displayName(resolver, uri) }
                        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallbackName
                    _state.update { it.copy(pendingImport = PendingImport(text, name), importMessage = null) }
                }
            }
        }
    }

    fun cancelImport() = _state.update { it.copy(pendingImport = null) }

    fun confirmImport(name: String, language: String) {
        val pending = _state.value.pendingImport ?: return
        val lab = decks ?: return
        _state.update { it.copy(pendingImport = null) }
        viewModelScope.launch {
            when (val outcome = lab.import(pending.text, name.ifBlank { pending.suggestedName }, language)) {
                is ImportOutcome.Imported -> {
                    _state.update {
                        it.copy(
                            importMessage = ImportMessage.Imported(outcome.deck.size),
                            language = language,
                            languages = (it.languages + language).distinct(),
                            deckId = outcome.deck.id,
                        )
                    }
                    coverageKey = null
                    measure()
                }
                is ImportOutcome.Failed -> _state.update { it.copy(importMessage = ImportMessage.Failed(outcome.reason)) }
            }
        }
    }

    fun deleteDeck(id: String) {
        val lab = decks ?: return
        viewModelScope.launch {
            lab.delete(id)
            _state.update { it.copy(importMessage = null, deckId = it.deckId.takeIf { current -> current != id }) }
            coverageKey = null
            measure()
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val REBUILD_DELAY_MS = 250L
        const val MAX_BYTES = 5 * 1024 * 1024

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val decks = DeckLibrary(
                    lemmatizer = { app.lemmatizer },
                    store = VocabGapsStore(File(app.filesDir, VocabGapsStore.DIR)),
                    bundled = BundledDecks { name -> app.assets.open(name).bufferedReader().use { it.readText() } },
                )
                WordsViewModel(app.corpus, app.lemmatizer, decks, app.history.revisions()) { words, coverage -> writeCsv(app, words, coverage) }
            }
        }

        /** Into the cache folder the FileProvider shares; the next History export or prune removes it. */
        private suspend fun writeCsv(app: App, words: LanguageWords, coverage: DeckCoverage?): Uri = withContext(Dispatchers.IO) {
            val folder = File(app.cacheDir, Exports.FOLDER).apply { mkdirs() }
            folder.listFiles { file -> file.name.startsWith(CSV_PREFIX) }?.forEach { it.delete() }
            val name = "$CSV_PREFIX${words.language ?: "other"}-${LocalDate.now()}-"
            val file = File.createTempFile(name, ".csv", folder)
            file.writeText(WordViews.csv(words, coverage), Charsets.UTF_8)
            FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }

        private const val CSV_PREFIX = "linguize-words-"
    }
}

/** UTF-8 by default; UTF-16 when the file says so; Windows-1252 when it isn't valid UTF-8 (older spreadsheet exports). */
internal fun decodeDeck(bytes: ByteArray): String {
    if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) {
        return String(bytes, Charsets.UTF_16)
    }
    val utf8 = String(bytes, Charsets.UTF_8)
    return if ('�' in utf8) String(bytes, charset("windows-1252")) else utf8
}

/** InputStream.readNBytes needs API 33; this reads up to [limit] bytes on any version. */
private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

