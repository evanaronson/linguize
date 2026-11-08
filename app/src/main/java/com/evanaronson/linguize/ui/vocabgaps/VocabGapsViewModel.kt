package com.evanaronson.linguize.ui.vocabgaps

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.DeckChoice
import com.evanaronson.linguize.ImportOutcome
import com.evanaronson.linguize.VocabGapsLab
import com.evanaronson.linguize.VocabView
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class WordFilter { All, Unused, Used }

/** A file picked for import, waiting for a name and a language. */
data class PendingImport(val text: String, val suggestedName: String)

/** The last import's outcome, shown under the decks. */
sealed interface ImportMessage {
    data class Imported(val words: Int) : ImportMessage
    data class Failed(val reason: DeckParse.Reason) : ImportMessage
    data object Unreadable : ImportMessage
    data object TooLarge : ImportMessage
}

data class VocabGapsState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val languages: List<String> = emptyList(),
    val language: String? = null,
    val decks: List<DeckChoice> = emptyList(),
    val deckId: String? = null,
    /** By lexeme (lemma and part of speech) or by lemma. */
    val level: VocabLevel = VocabLevel.Lexeme,
    val view: VocabView? = null,
    val filter: WordFilter = WordFilter.All,
    val imported: List<ImportedDeck> = emptyList(),
    val pendingImport: PendingImport? = null,
    val importMessage: ImportMessage? = null,
)

/**
 * Vocabulary gaps: the corpus is read again each time the screen asks, so input deleted
 * elsewhere is gone from the counts; the report itself is reused while the evidence is unchanged.
 */
class VocabGapsViewModel internal constructor(private val lab: VocabGapsLab) : ViewModel() {
    private val _state = MutableStateFlow(VocabGapsState())
    val state: StateFlow<VocabGapsState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() = reload(_state.value.language, _state.value.deckId)

    fun setLevel(level: VocabLevel) {
        if (level != _state.value.level) {
            _state.update { it.copy(level = level) }
            reload(_state.value.language, _state.value.deckId)
        }
    }

    fun selectLanguage(language: String) {
        if (language != _state.value.language) reload(language, null)
    }

    fun selectDeck(id: String) {
        if (id != _state.value.deckId) reload(_state.value.language, id)
    }

    fun setFilter(filter: WordFilter) = _state.update { it.copy(filter = filter) }

    private fun reload(language: String?, deckId: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val saved = if (language == null) lab.selection() else VocabSelection(language, deckId, _state.value.level)
            val corpus = lab.corpus()
            val languages = lab.languages(corpus)
            val chosen = saved.language?.takeIf { it in languages } ?: languages.firstOrNull()
            val decks = chosen?.let { lab.decks(it) }.orEmpty()
            val deck = decks.firstOrNull { it.id == (deckId ?: saved.deckId) } ?: decks.firstOrNull()
            val view = deck?.let { runCatching { lab.load(it.id, saved.level, corpus) }.getOrNull() }
            val keepView = view?.deck?.id == _state.value.view?.deck?.id
            _state.update {
                it.copy(
                    loading = false,
                    failed = deck != null && view == null,
                    languages = languages,
                    language = chosen,
                    decks = decks,
                    deckId = deck?.id,
                    level = saved.level,
                    view = view,
                    filter = if (keepView) it.filter else WordFilter.All,
                    imported = lab.imported(),
                )
            }
            if (chosen != null) lab.select(VocabSelection(chosen, deck?.id, saved.level))
        }
    }

    /** Reads a picked document; a deck is at most a few hundred kilobytes of text. */
    fun pick(resolver: ContentResolver, uri: Uri, fallbackName: String) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
                    if (size != null && size > MAX_BYTES) return@runCatching null
                    val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_BYTES + 1) }
                        ?: error("No stream")
                    if (bytes.size > MAX_BYTES) null else decode(bytes)
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
        _state.update { it.copy(pendingImport = null) }
        viewModelScope.launch {
            val message = when (val outcome = lab.import(pending.text, name.ifBlank { pending.suggestedName }, language)) {
                is ImportOutcome.Imported -> {
                    _state.update { it.copy(importMessage = ImportMessage.Imported(outcome.deck.size)) }
                    reload(language, outcome.deck.id)
                    return@launch
                }
                is ImportOutcome.Failed -> ImportMessage.Failed(outcome.reason)
            }
            _state.update { it.copy(importMessage = message) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            lab.delete(id)
            _state.update { it.copy(importMessage = null) }
            val current = _state.value
            reload(current.language, current.deckId.takeIf { it != id })
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024

        fun factory(app: App) = viewModelFactory {
            initializer {
                VocabGapsViewModel(
                    VocabGapsLab(
                        utterances = { app.corpus.utterances() },
                        lemmatizer = { app.lemmatizer },
                        store = VocabGapsStore(File(app.filesDir, VocabGapsStore.DIR)),
                        bundled = BundledDecks { name -> app.assets.open(name).bufferedReader().use { it.readText() } },
                    ),
                )
            }
        }
    }
}

/** UTF-8 by default; UTF-16 when the file says so; Windows-1252 when it isn't valid UTF-8 (older spreadsheet exports). */
internal fun decode(bytes: ByteArray): String {
    if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) {
        return String(bytes, Charsets.UTF_16)
    }
    val utf8 = String(bytes, Charsets.UTF_8)
    return if ('�' in utf8) String(bytes, charset("windows-1252")) else utf8
}

/** InputStream.readNBytes needs API 33; this reads up to [limit] bytes on any version. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
