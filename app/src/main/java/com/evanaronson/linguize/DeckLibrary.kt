package com.evanaronson.linguize

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.DeckParser
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A deck the writer can pick: [name] is empty for a bundled deck, which the screen names.
 * [formsOnly] decks list written forms that no morphology has grouped yet.
 */
data class DeckChoice(
    val id: String,
    val name: String,
    val language: String,
    val size: Int,
    val bundled: Boolean,
    val formsOnly: Boolean = false,
)

/** What importing a file gave. */
sealed interface ImportOutcome {
    data class Imported(val deck: ImportedDeck, val skipped: Int) : ImportOutcome
    data class Failed(val reason: DeckParse.Reason) : ImportOutcome
}

/**
 * Lab, Words: the frequency decks the Words screen measures the writer's words against, bundled
 * or imported, and the last language and deck chosen. Coverage itself is counted from the word
 * index ([com.evanaronson.linguize.core.words.DeckCoverage]), not here.
 */
class DeckLibrary(
    private val lemmatizer: () -> Lemmatizer,
    private val store: VocabGapsStore,
    private val bundled: BundledDecks,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Languages with a deck, bundled first. */
    suspend fun languages(): List<String> = withContext(io) {
        (BundledDecks.languages + store.imported().map { it.language }).distinct()
    }

    suspend fun decks(language: String): List<DeckChoice> = withContext(io) {
        val own = BundledDecks.languages.takeIf { language in it }?.let { deck(BundledDecks.idFor(language)) }
            ?.let { listOf(DeckChoice(it.id, "", language, it.size, bundled = true, formsOnly = it.formsOnly)) }.orEmpty()
        own + store.imported().filter { it.language == language }.map { DeckChoice(it.id, it.name, it.language, it.size, bundled = false) }
    }

    suspend fun imported(): List<ImportedDeck> = withContext(io) { store.imported() }

    /**
     * The deck with [id], read once per morphology and kept: a bundled deck is grouped into
     * lexemes with the lemmatizer, an imported one parsed from its file. Null when it's gone.
     */
    suspend fun deck(id: String): VocabDeck? = withContext(compute) {
        val morphology = lemmatizer()
        synchronized(decks) { decks[id]?.takeIf { it.first === morphology }?.second }
            ?: read(id, morphology)?.also { deck -> synchronized(decks) { decks[id] = morphology to deck } }
    }

    private val decks = HashMap<String, Pair<Lemmatizer, VocabDeck>>()

    private fun read(id: String, morphology: Lemmatizer): VocabDeck? =
        if (id.startsWith(VocabGapsStore.IMPORTED_PREFIX)) store.importedDeck(id)
        else BundledDecks.languages.firstOrNull { BundledDecks.idFor(it) == id }?.let { bundled.deck(it, morphology) }

    suspend fun import(text: String, name: String, language: String): ImportOutcome = withContext(io) {
        when (val parsed = DeckParser.parse(text, language)) {
            is DeckParse.Failed -> ImportOutcome.Failed(parsed.reason)
            is DeckParse.Parsed -> ImportOutcome.Imported(store.import(name, language, parsed.entries), parsed.skipped)
        }
    }

    suspend fun delete(id: String) = withContext(io) {
        try {
            store.delete(id)
        } finally {
            // A cleanup failure may follow the committed index update. Re-read durable state.
            synchronized(decks) { decks.remove(id) }
        }
    }

    suspend fun selection(): VocabSelection = withContext(io) { store.selection() }

    suspend fun select(selection: VocabSelection, current: () -> Boolean = { true }): Boolean = withContext(io) {
        store.saveSelection(selection, current)
    }
}
