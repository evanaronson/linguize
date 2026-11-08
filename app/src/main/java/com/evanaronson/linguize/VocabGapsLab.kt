package com.evanaronson.linguize

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.DeckParser
import com.evanaronson.linguize.core.vocab.VocabAnalyser
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.vocab.VocabReport
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

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

/**
 * One deck's coverage, with the utterances its examples point into. [hasLexemes] when the
 * deck has parts of speech, so it can be viewed by lexeme or by lemma.
 */
data class VocabView(
    val deck: VocabDeck,
    val report: VocabReport,
    val utterances: Map<String, Utterance>,
    val hasLexemes: Boolean = false,
)

/** What importing a file gave. */
sealed interface ImportOutcome {
    data class Imported(val deck: ImportedDeck, val skipped: Int) : ImportOutcome
    data class Failed(val reason: DeckParse.Reason) : ImportOutcome
}

/**
 * Lab, Vocabulary gaps: the writer's own words (the corpus) against a frequency deck. Reports
 * are derived on demand and saved with the fingerprint of their evidence, so any change to the
 * corpus, the deck or the morphology rebuilds them; deleting input lowers the numbers.
 */
class VocabGapsLab(
    private val utterances: suspend () -> List<Utterance>,
    private val lemmatizer: () -> Lemmatizer,
    private val store: VocabGapsStore,
    private val bundled: BundledDecks,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Reads the corpus as it is now. Deleted input is already gone from it. */
    suspend fun corpus(): List<Utterance> = utterances()

    /** Languages with a deck: those the writer uses most first, then the rest. */
    suspend fun languages(corpus: List<Utterance>): List<String> = withContext(Dispatchers.IO) {
        val withDecks = (BundledDecks.languages + store.imported().map { it.language }).distinct()
        val used = corpus.mapNotNull { it.language }.groupingBy { it }.eachCount()
        withDecks.sortedWith(compareByDescending<String> { used[it] ?: 0 }.thenBy { withDecks.indexOf(it) })
    }

    suspend fun decks(language: String): List<DeckChoice> = withContext(Dispatchers.IO) {
        val own = bundled.deck(language, lemmatizer())
            ?.let { listOf(DeckChoice(it.id, "", language, it.size, bundled = true, formsOnly = it.formsOnly)) }.orEmpty()
        own + store.imported().filter { it.language == language }.map { DeckChoice(it.id, it.name, it.language, it.size, bundled = false) }
    }

    suspend fun imported(): List<ImportedDeck> = withContext(Dispatchers.IO) { store.imported() }

    /**
     * One deck's report at [level], reused when the evidence is unchanged and rebuilt when it
     * isn't. A deck without parts of speech has only the lemma view.
     */
    suspend fun load(deckId: String, level: VocabLevel, corpus: List<Utterance>): VocabView? = withContext(Dispatchers.Default) {
        val morphology = lemmatizer()
        val lexemes = deck(deckId, morphology) ?: return@withContext null
        val deck = if (level == VocabLevel.Lemma || !lexemes.hasLexemes) lexemes.byLemma() else lexemes
        val fingerprint = VocabAnalyser.fingerprint(deck, corpus, morphology)
        val report = store.report(deck.id, deck.level, fingerprint)
            ?: VocabAnalyser(morphology, zone()).analyse(deck, corpus).also { runCatching { store.saveReport(it) } }
        VocabView(deck, report, corpus.filter { it.language == deck.language }.associateBy { it.id }, lexemes.hasLexemes)
    }

    private fun deck(id: String, morphology: Lemmatizer): VocabDeck? =
        if (id.startsWith(VocabGapsStore.IMPORTED_PREFIX)) store.importedDeck(id)
        else BundledDecks.languages.firstOrNull { BundledDecks.idFor(it) == id }?.let { bundled.deck(it, morphology) }

    suspend fun import(text: String, name: String, language: String): ImportOutcome = withContext(Dispatchers.IO) {
        when (val parsed = DeckParser.parse(text, language)) {
            is DeckParse.Failed -> ImportOutcome.Failed(parsed.reason)
            is DeckParse.Parsed -> ImportOutcome.Imported(store.import(name, language, parsed.entries), parsed.skipped)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { store.delete(id) }

    suspend fun selection(): VocabSelection = withContext(Dispatchers.IO) { store.selection() }

    suspend fun select(selection: VocabSelection) = withContext(Dispatchers.IO) { runCatching { store.saveSelection(selection) } }
}
