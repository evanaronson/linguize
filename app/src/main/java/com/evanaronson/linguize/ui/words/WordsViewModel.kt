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
import com.evanaronson.linguize.core.corpus.CorpusReadStatus
import com.evanaronson.linguize.core.corpus.CorpusSourceCoverage
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
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/** A file picked for import, waiting for a name and a language. */
data class PendingImport(val text: String, val suggestedName: String)

/** The last import's outcome, shown under the decks. */
sealed interface ImportMessage {
    data class Imported(val words: Int) : ImportMessage
    data class Failed(val reason: DeckParse.Reason) : ImportMessage
    data object Unreadable : ImportMessage
    data object TooLarge : ImportMessage
    data object SaveFailed : ImportMessage
}

/** A storage operation the screen can retry without discarding the last usable result. */
sealed interface WordsStorageFailure {
    data object ReadDecks : WordsStorageFailure
    data object ReadSelection : WordsStorageFailure
    data object SaveSelection : WordsStorageFailure
    data class DeleteDeck(val id: String) : WordsStorageFailure
}

data class WordsState(
    /** True until the first build finishes. */
    val loading: Boolean = true,
    /** The current scope/evidence has not finished producing one coherent result yet. */
    val measuring: Boolean = true,
    /** Export is available only after the complete current query has been published. */
    val exportReady: Boolean = false,
    /** Status and bounds of the latest evidence read; incomplete reads never replace [index]. */
    val evidenceStatus: CorpusReadStatus? = null,
    val evidenceSources: List<CorpusSourceCoverage> = emptyList(),
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
    val importing: Boolean = false,
    val importMessage: ImportMessage? = null,
    val storageFailure: WordsStorageFailure? = null,
) {
    val evidenceFailed: Boolean get() = evidenceStatus == CorpusReadStatus.Partial || evidenceStatus == CorpusReadStatus.Failed

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
    data class Ready(val uri: Uri, val language: String?, val file: File) : WordsExport
    data object Failed : WordsExport
}

/**
 * Words: everything the writer typed, against a frequency deck, for Progress, the Words page
 * and a word's page. One pass: the corpus is read once into the word index (cached by its
 * fingerprint, rebuilt after every History write and whenever a screen asks), and the chosen
 * deck's coverage is counted from that index, cached by the index fingerprint, deck and level.
 */
class WordsViewModel internal constructor(
    private val corpus: Corpus,
    private val lemmatizer: Lemmatizer,
    private val decks: DeckLibrary?,
    revisions: Flow<Long>,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val exports: Exports? = null,
    private val shareFile: (File) -> Uri = { Uri.fromFile(it) },
    private val countCoverage: suspend (LanguageWords, VocabDeck) -> DeckCoverage = { words, deck ->
        withContext(compute) { DeckCoverage.build(words, deck, lemmatizer) }
    },
) : ViewModel() {
    private val cache = WordIndexCache(lemmatizer)
    private val _state = MutableStateFlow(WordsState())
    val state: StateFlow<WordsState> = _state.asStateFlow()

    private val _export = MutableStateFlow<WordsExport?>(null)
    val export: StateFlow<WordsExport?> = _export.asStateFlow()

    private data class CoverageKey(val fingerprint: String, val language: String?, val deckId: String?, val level: WordLevel)
    private data class ReadyWords(
        val query: Long, val key: CoverageKey, val words: LanguageWords,
        val coverage: DeckCoverage?, val exportGeneration: Long,
    )

    private val queries = AtomicLong()
    private val selectionWrites = Mutex()
    private val deleteWrites = Mutex()
    private val failedDeletes = LinkedHashSet<String>()
    private var queryJob: Job? = null
    private var needsRebuild = true
    private var indexGeneration: Long? = null
    private var lastCoverage: Pair<CoverageKey, DeckCoverage?>? = null
    private var readyWords: ReadyWords? = null
    private var restored = false
    private var languageChosen = false
    private var deckChosen = false
    private var levelChosen = false
    private var scopeVersion = 0L
    private var exportVersion = 0L
    private var exportJob: Job? = null
    private var pickVersion = 0L
    private var pickJob: Job? = null
    private var importVersion = 0L

    init {
        viewModelScope.launch {
            // Invalidate immediately, then debounce the expensive read. A delayed revision must
            // never leave an old calculation or export eligible to publish in the meantime.
            revisions.collect { requestQuery(rebuild = true, delayed = true) }
        }
    }

    /** Reads the corpus again; cheap when nothing changed. */
    fun refresh() { requestQuery(rebuild = true, delayed = true) }

    /** A list follows an explicit language; a detail also pins its level and nullable language. */
    fun enter(language: String?, level: WordLevel? = null) {
        val chooseLanguage = if (language != null || level != null) languageChange(language) else null
        val chooseLevel = level?.let(::levelChange)
        requestQuery(rebuild = true, delayed = true, clearCoverage = chooseLanguage != null || chooseLevel != null) {
            val scoped = chooseLanguage?.invoke(it) ?: it
            chooseLevel?.invoke(scoped) ?: scoped
        }
    }

    private fun current(query: Long) = queries.get() == query

    private fun requestQuery(
        rebuild: Boolean = false,
        delayed: Boolean = false,
        clearCoverage: Boolean = false,
        change: (WordsState) -> WordsState = { it },
    ): Job {
        val query = queries.incrementAndGet()
        queryJob?.cancel()
        invalidateExport()
        readyWords = null
        needsRebuild = needsRebuild || rebuild
        _state.update {
            val next = change(it)
            if (clearCoverage) next.copy(measuring = true, exportReady = false, coverage = null, decks = emptyList(), deckFailed = false)
            else next.copy(measuring = true, exportReady = false)
        }
        return viewModelScope.launch {
            if (delayed) delay(REBUILD_DELAY_MS)
            loadQuery(query)
        }.also { queryJob = it }
    }

    private suspend fun loadQuery(query: Long) {
        val exportGeneration = exports?.generation() ?: 0L
        val before = _state.value
        val evidence = if (needsRebuild || before.index == null || indexGeneration != exportGeneration) {
            withContext(compute) { corpus.read() }
        } else null
        if (!current(query)) return
        if (evidence != null && evidence.status != CorpusReadStatus.Complete) {
            needsRebuild = true
            // Deck membership is independent durable state. In particular, a deletion can
            // commit membership before physical cleanup fails while History is unavailable.
            var inventoryFailed = false
            val imported = try { decks?.imported() } catch (_: IOException) { inventoryFailed = true; null }
            if (!current(query)) return
            val ids = imported?.mapTo(HashSet()) { it.id }
            _state.update {
                val removed = ids != null && it.deckId?.startsWith(VocabGapsStore.IMPORTED_PREFIX) == true && it.deckId !in ids
                it.copy(loading = false, measuring = false, exportReady = false,
                    evidenceStatus = evidence.status, evidenceSources = evidence.sources,
                    imported = imported ?: it.imported,
                    decks = if (ids == null) it.decks else it.decks.filter { deck -> deck.bundled || deck.id in ids },
                    deckId = it.deckId.takeUnless { removed }, coverage = it.coverage.takeUnless { removed },
                    storageFailure = if (inventoryFailed) WordsStorageFailure.ReadDecks else it.storageFailure)
            }
            return
        }
        val index = if (evidence != null) withContext(compute) { cache.index(evidence.utterances) }
            else requireNotNull(before.index)
        if (!current(query)) return

        var readFailure: WordsStorageFailure? = null
        if (!restored && decks != null) {
            try {
                val saved = decks.selection()
                if (!current(query)) return
                restored = true
                _state.update {
                    it.copy(
                        language = if (languageChosen) it.language else saved.language,
                        deckId = if (deckChosen || (it.language != null && it.language != saved.language)) it.deckId else saved.deckId,
                        level = if (levelChosen) it.level else if (saved.level == VocabLevel.Lemma) WordLevel.Lemmas else null,
                        coverage = null, decks = emptyList(), deckFailed = false,
                        storageFailure = it.storageFailure.takeUnless { failure -> failure == WordsStorageFailure.ReadSelection },
                    )
                }
            } catch (_: IOException) {
                if (!current(query)) return
                readFailure = WordsStorageFailure.ReadSelection
            }
        }
        val intent = _state.value
        val written = index.languages.mapNotNull { it.language }
        val languages: List<String>
        val choices: List<DeckChoice>
        val imported: List<ImportedDeck>
        val words: LanguageWords
        val choice: DeckChoice?
        val deck: VocabDeck?
        try {
            val deckLanguages = decks?.languages().orEmpty()
            if (!current(query)) return
            languages = (written + deckLanguages).distinct()
            val language = intent.language ?: languages.firstOrNull()
            words = if (language == null) index.languages.firstOrNull() ?: LanguageWords.empty(null)
                else index.language(language) ?: LanguageWords.empty(language)
            choices = if (words.language != null) decks?.decks(words.language).orEmpty() else emptyList()
            if (!current(query)) return
            imported = decks?.imported().orEmpty()
            if (!current(query)) return
            choice = choices.firstOrNull { it.id == intent.deckId } ?: choices.firstOrNull()
            deck = choice?.let { decks?.deck(it.id) }
            if (!current(query)) return
        } catch (_: IOException) {
            if (!current(query)) return
            _state.update {
                // Keep a previous result intact on read failure. On first load, writer evidence
                // can still be shown without pretending the failed deck read was an empty list.
                it.copy(loading = false, measuring = false, index = it.index ?: index,
                    evidenceStatus = CorpusReadStatus.Complete, evidenceSources = evidence?.sources ?: before.evidenceSources,
                    languages = if (it.index == null) written else it.languages,
                    storageFailure = WordsStorageFailure.ReadDecks)
            }
            return
        }
        val level = if (words.morphology) intent.level ?: WordLevel.Lexemes else WordLevel.Forms
        val key = CoverageKey(index.fingerprint, words.language, choice?.id, level)
        val coverage = lastCoverage?.takeIf { it.first == key }?.second ?: deck?.let {
            val variant = if (level == WordLevel.Lemmas || !it.hasLexemes) it.byLemma() else it
            countCoverage(words, variant)
        }
        if (!current(query)) return
        if (exports != null && exports.generation() != exportGeneration) {
            requestQuery(rebuild = true, delayed = true)
            return
        }

        // Every field that describes a measurement changes together. Filters/import feedback
        // remain current because they are independent inputs owned by their own actions.
        lastCoverage = key to coverage
        indexGeneration = exportGeneration
        needsRebuild = false
        readyWords = ReadyWords(query, key, words, coverage, exportGeneration)
        _state.update {
            it.copy(loading = false, measuring = false, exportReady = true, index = index, languages = languages,
                evidenceStatus = CorpusReadStatus.Complete, evidenceSources = evidence?.sources ?: before.evidenceSources,
                decks = choices, deckId = choice?.id, coverage = coverage,
                deckFailed = choice != null && deck == null, imported = imported,
                storageFailure = readFailure ?: pendingDeleteFailure() ?: it.storageFailure.takeUnless { failure ->
                    failure == WordsStorageFailure.ReadDecks || failure == WordsStorageFailure.ReadSelection
                })
        }
        saveSelection(query, key)
    }

    private suspend fun saveSelection(query: Long, key: CoverageKey) {
        val lab = decks ?: return
        val language = key.language ?: return
        if (!restored) return
        // The IO write checks the same atomic token under the store's lock. Serialization also
        // ensures an already-started old write cannot finish after the newer selection is saved.
        selectionWrites.withLock {
            if (!current(query)) return
            try {
                val saved = lab.select(VocabSelection(language, key.deckId,
                    if (key.level == WordLevel.Lemmas) VocabLevel.Lemma else VocabLevel.Lexeme)) { current(query) }
                if (saved && current(query)) clearStorageFailure(WordsStorageFailure.SaveSelection)
            } catch (_: IOException) {
                if (current(query)) _state.update { it.copy(storageFailure = WordsStorageFailure.SaveSelection) }
            }
        }
    }

    private fun clearStorageFailure(failure: WordsStorageFailure) = _state.update {
        if (it.storageFailure == failure) it.copy(storageFailure = pendingDeleteFailure()) else it
    }

    fun retryStorage() {
        when (val failure = _state.value.storageFailure) {
            is WordsStorageFailure.DeleteDeck -> deleteDeck(failure.id)
            WordsStorageFailure.SaveSelection -> requestQuery()
            WordsStorageFailure.ReadDecks, WordsStorageFailure.ReadSelection -> requestQuery(rebuild = true)
            null -> Unit
        }
    }

    fun selectLanguage(language: String?) {
        languageChange(language)?.let { requestQuery(clearCoverage = true, change = it) }
    }

    private fun languageChange(language: String?): ((WordsState) -> WordsState)? {
        val changed = !languageChosen || language != _state.value.language
        languageChosen = true
        if (!changed) return null
        scopeVersion++
        return { it.copy(language = language, deckId = null, filter = WordFilter.All, query = "") }
    }

    fun selectDeck(id: String) {
        val changed = !deckChosen || id != _state.value.deckId
        deckChosen = true
        if (!changed) return
        scopeVersion++
        requestQuery(clearCoverage = true) { it.copy(deckId = id) }
    }

    fun countBy(level: WordLevel) {
        levelChange(level)?.let { requestQuery(clearCoverage = true, change = it) }
    }

    private fun levelChange(level: WordLevel): ((WordsState) -> WordsState)? {
        val changed = !levelChosen || level != _state.value.level
        levelChosen = true
        if (!changed) return null
        scopeVersion++
        return { it.copy(level = level) }
    }

    fun sortBy(sort: WordSort) = _state.update { it.copy(sort = sort) }
    fun filter(filter: WordFilter) = _state.update { it.copy(filter = filter) }
    fun search(query: String) = _state.update { it.copy(query = query) }

    private fun invalidateExport() {
        exportVersion++
        exportJob?.cancel()
        val obsolete = _export.value as? WordsExport.Ready
        _export.value = null
        if (obsolete != null && exports != null) viewModelScope.launch(io) { exports.discard(obsolete.file) }
    }

    /** Writes only a complete result belonging to the current query and source generation. */
    fun exportCsv() {
        val writer = exports ?: return
        val snapshot = readyWords?.takeIf { current(it.query) && !_state.value.measuring } ?: return
        if (writer.generation() != snapshot.exportGeneration) {
            // A deletion may invalidate exports without another History revision. Re-read the
            // evidence instead of granting a new ticket to an old snapshot forever.
            requestQuery(rebuild = true)
            return
        }
        invalidateExport()
        val request = writer.requestWords(snapshot.exportGeneration)
        val version = exportVersion
        exportJob = viewModelScope.launch {
            var file: File? = null
            var offered = false
            try {
                withContext(io) {
                    file = writer.writeWords(LocalDate.now(), snapshot.key.language, request) { output ->
                        output.write(WordViews.csv(snapshot.words, snapshot.coverage).toByteArray(Charsets.UTF_8))
                        true
                    }
                }
                if (version != exportVersion || !current(snapshot.query)) return@launch
                val ready = file?.takeIf(writer::isCurrent)
                if (ready != null) {
                    _export.value = WordsExport.Ready(shareFile(ready), snapshot.key.language, ready)
                    offered = true
                } else if (writer.generation() != snapshot.exportGeneration) {
                    requestQuery(rebuild = true)
                } else {
                    _export.value = WordsExport.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                if (version == exportVersion && current(snapshot.query)) _export.value = WordsExport.Failed
            } catch (_: IllegalArgumentException) {
                if (version == exportVersion && current(snapshot.query)) _export.value = WordsExport.Failed
            } finally {
                if (!offered) file?.let { obsolete ->
                    withContext(NonCancellable + io) { writer.discard(obsolete) }
                }
            }
        }
    }

    /** Rechecked immediately before handing the URI to another app. */
    fun isExportCurrent(result: WordsExport.Ready): Boolean =
        _export.value == result && exports?.isCurrent(result.file) == true

    fun exportHandled() { _export.value = null }

    /** Reads a picked document; only the newest picker result can open the import dialog. */
    fun pick(resolver: ContentResolver, uri: Uri, fallbackName: String) {
        val version = ++pickVersion
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            val read = withContext(io) {
                try {
                    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
                    if (size != null && size > MAX_BYTES) return@withContext Result.success(null)
                    val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES + 1) } ?: error("No stream")
                    Result.success(if (bytes.size > MAX_BYTES) null else decodeDeck(bytes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (version != pickVersion) return@launch
            val text = read.getOrNull()
            when {
                read.isFailure -> _state.update { it.copy(importMessage = ImportMessage.Unreadable) }
                text == null -> _state.update { it.copy(importMessage = ImportMessage.TooLarge) }
                else -> {
                    val name = withContext(io) { displayName(resolver, uri) }
                        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallbackName
                    if (version == pickVersion) _state.update { it.copy(pendingImport = PendingImport(text, name), importMessage = null) }
                }
            }
        }
    }

    fun cancelImport() {
        if (_state.value.importing) return
        pickVersion++
        pickJob?.cancel()
        _state.update { it.copy(pendingImport = null) }
    }

    fun confirmImport(name: String, language: String) {
        val pending = _state.value.pendingImport ?: return
        val lab = decks ?: return
        if (_state.value.importing) return
        val version = ++importVersion
        val picked = pickVersion
        val selected = scopeVersion
        _state.update { it.copy(importing = true, importMessage = null) }
        // An accepted mutation is not a child of the replaceable query job.
        viewModelScope.launch {
            try {
                when (val outcome = lab.import(pending.text, name.ifBlank { pending.suggestedName }, language)) {
                    is ImportOutcome.Imported -> {
                        _state.update {
                            it.copy(imported = it.imported.filterNot { deck -> deck.id == outcome.deck.id } + outcome.deck,
                                pendingImport = if (picked == pickVersion) null else it.pendingImport,
                                importMessage = if (picked == pickVersion) ImportMessage.Imported(outcome.deck.size) else it.importMessage)
                        }
                        val selectImported = selected == scopeVersion
                        if (selectImported) {
                            scopeVersion++
                            languageChosen = true
                            deckChosen = true
                        }
                        requestQuery(clearCoverage = selectImported) {
                            if (selectImported) it.copy(language = language, deckId = outcome.deck.id) else it
                        }.join()
                    }
                    is ImportOutcome.Failed -> if (picked == pickVersion) _state.update {
                        it.copy(pendingImport = null, importMessage = ImportMessage.Failed(outcome.reason))
                    }
                }
            } catch (_: IOException) {
                if (picked == pickVersion) _state.update { it.copy(importMessage = ImportMessage.SaveFailed) }
            } finally {
                if (version == importVersion) _state.update { it.copy(importing = false) }
            }
        }
    }

    fun deleteDeck(id: String) {
        val lab = decks ?: return
        viewModelScope.launch {
            deleteWrites.withLock {
                try {
                    lab.delete(id)
                } catch (_: IOException) {
                    // Each failed command keeps its exact cleanup ID, even if a later deletion
                    // succeeds. Membership may already have committed, so refresh it first.
                    failedDeletes += id
                    requestQuery().join()
                    publishDeleteFailure()
                    return@withLock
                }
                failedDeletes -= id
                publishDeleteFailure()
                _state.update { it.copy(importMessage = null) }
                requestQuery(clearCoverage = _state.value.deckId == id) {
                    it.copy(deckId = it.deckId.takeIf { current -> current != id },
                        imported = it.imported.filterNot { deck -> deck.id == id },
                        decks = it.decks.filterNot { deck -> deck.id == id })
                }
            }
        }
    }

    private fun pendingDeleteFailure() = failedDeletes.firstOrNull()?.let { WordsStorageFailure.DeleteDeck(it) }

    private fun publishDeleteFailure() = _state.update {
        it.copy(storageFailure = pendingDeleteFailure() ?: it.storageFailure.takeUnless { failure -> failure is WordsStorageFailure.DeleteDeck })
    }

    override fun onCleared() {
        queries.incrementAndGet()
        pickVersion++
        importVersion++
        exportVersion++
        readyWords = null
        _export.value = null
        super.onCleared()
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
                    bundled = app.bundledDecks,
                )
                WordsViewModel(
                    app.corpus, app.lemmatizer, decks, app.history.revisions(), exports = app.exports,
                    shareFile = { file -> FileProvider.getUriForFile(app, "${app.packageName}.files", file) },
                )
            }
        }

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

