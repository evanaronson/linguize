package com.evanaronson.linguize.ui.phrasebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer

/** Phrases of one language, newest first; [language] is null for phrases saved without one. */
data class PhraseSection(val language: String?, val phrases: List<Phrase>)

/** What the Phrasebook screen shows. */
data class PhrasebookState(
    /** Every saved phrase, before the search. */
    val total: Int,
    val query: String,
    /** The phrases that match [query], by language. */
    val sections: List<PhraseSection>,
    /** Lab, Language scope: the language shown, which a phrase added by hand starts in; null is every language. */
    val language: String? = null,
) {
    val matching get() = sections.sumOf { it.phrases.size }
}

/** Search ignores case and accents, so "esta" finds "està", over the phrase, what it replaced and its meaning. */
fun filterPhrases(phrases: List<Phrase>, query: String): List<Phrase> {
    val terms = query.searchable().split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return phrases
    return phrases.filter { phrase ->
        val haystack = listOfNotNull(phrase.phrase, phrase.replaced, phrase.meaning).joinToString(" ").searchable()
        terms.all { it in haystack }
    }
}

/** Lab, Language scope: the phrases in [language] (null: all). A phrase with no language shows only under all. */
fun inLanguage(phrases: List<Phrase>, language: String?): List<Phrase> =
    phrases.filter { LanguageScope.keeps(it.language, language) }

/** Sections in the order the language pickers list languages, then any others, then unknown. */
fun groupByLanguage(phrases: List<Phrase>): List<PhraseSection> {
    val known = Language.all.map { it.code }
    return phrases.groupBy { it.language }
        .map { (language, rows) -> PhraseSection(language, rows.sortedByDescending { it.createdAt }) }
        .sortedWith(compareBy({ section ->
            when (val index = known.indexOf(section.language)) {
                -1 -> if (section.language == null) known.size + 1 else known.size
                else -> index
            }
        }, { it.language.orEmpty() }))
}

/** The words a localized export needs; the screen fills them from resources. */
class PhraseTextLabels(
    val title: String,
    val language: (String?) -> String,
    val insteadOf: (String) -> String,
)

/** The phrasebook as plain text to share or paste: a heading per language, then each phrase. */
fun phrasebookText(sections: List<PhraseSection>, labels: PhraseTextLabels): String = buildString {
    append(labels.title)
    for (section in sections) {
        append("\n\n").append(labels.language(section.language))
        for (phrase in section.phrases) {
            append("\n\n").append(phrase.phrase)
            phrase.replaced?.let { append('\n').append(labels.insteadOf(it)) }
            phrase.meaning?.let { append('\n').append(it) }
        }
    }
    append('\n')
}

private fun String.searchable(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()

enum class PhrasebookFailure { Load, Save, Delete, Restore }

/**
 * The Phrasebook screen's state: search, add, and delete with Undo. A deleted phrase is
 * deleted at once and kept here until Undo or the next deletion, so Undo survives a
 * configuration change but not the process.
 */
class PhrasebookViewModel internal constructor(
    private val store: PhrasebookStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /** Lab, Language scope: the language shown; null is every language. */
    private val language = MutableStateFlow<String?>(null)
    private val deleted = MutableStateFlow<Phrase?>(null)
    private val failed = MutableStateFlow<PhrasebookFailure?>(null)
    val failure: StateFlow<PhrasebookFailure?> = failed.asStateFlow()
    private var failedDelete: String? = null
    /** Requested Undo operations must survive later additions/deletions and their snackbars. */
    private val pendingRestores = LinkedHashMap<String, Phrase>()

    /** The phrase that was just deleted, while its Undo can still bring it back. */
    val lastDeleted: StateFlow<Phrase?> = deleted.asStateFlow()

    /** Null until the phrasebook has been read. */
    val state: StateFlow<PhrasebookState?> = combine(store.phrases, query, language) { phrases, query, language ->
        phrases?.let { all ->
            val scoped = inLanguage(all, language)
            PhrasebookState(scoped.size, query, groupByLanguage(filterPhrases(scoped, query)), language)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Lab, Language scope: show only [language]'s phrases. */
    fun setLanguage(language: String?) {
        this.language.value = language
    }

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                withContext(io) { store.load() }
                recovered()
            } catch (_: IOException) {
                reportFailure(PhrasebookFailure.Load)
            }
        }
    }

    /** Save is retried with the editor's current contents, so an older draft cannot replace new input. */
    fun retry() {
        when (failed.value) {
            PhrasebookFailure.Load -> load()
            PhrasebookFailure.Delete -> failedDelete?.let(::delete)
            PhrasebookFailure.Restore -> pendingRestores.values.toList().forEach(::restore)
            else -> Unit
        }
    }

    fun search(text: String) {
        query.value = text
    }

    /** Saves a typed-in phrase; false leaves the editor open after invalid input or a failed write. */
    fun add(draft: PhraseDraft, onSaved: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val saved = withContext(io) { store.save(draft) }
                recovered()
                onSaved(saved != null)
            } catch (_: IOException) {
                reportFailure(PhrasebookFailure.Save)
                onSaved(false)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                withContext(io) { store.delete(id) }?.let { deleted.value = it }
                recovered()
                failedDelete = null
            } catch (_: IOException) {
                failedDelete = id
                reportFailure(PhrasebookFailure.Delete)
            }
        }
    }

    fun undoDelete() {
        val phrase = deleted.value ?: return
        restore(phrase)
    }

    private fun restore(phrase: Phrase) {
        viewModelScope.launch {
            try {
                withContext(io) { store.restore(phrase) }
                if (deleted.value == phrase) deleted.value = null
                pendingRestores.remove(phrase.id)
                recovered()
            } catch (_: IOException) {
                pendingRestores[phrase.id] = phrase
                reportFailure(PhrasebookFailure.Restore)
            }
        }
    }

    private fun recovered() {
        failed.value = if (pendingRestores.isEmpty()) null else PhrasebookFailure.Restore
    }

    private fun reportFailure(problem: PhrasebookFailure) {
        failed.value = if (pendingRestores.isEmpty()) problem else PhrasebookFailure.Restore
    }

    /** The Undo moment for [phrase] has passed. */
    fun deleteConfirmed(phrase: Phrase) {
        if (deleted.value == phrase && phrase.id !in pendingRestores) deleted.value = null
    }

    /** The phrases to export: every saved phrase, by language, regardless of the search. */
    fun allSections(): List<PhraseSection> = groupByLanguage(store.phrases.value.orEmpty())

    companion object {
        fun factory(store: PhrasebookStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { PhrasebookViewModel(store) }
        }
    }
}
