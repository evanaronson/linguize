package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance

/**
 * Deck rows used outside words Linguize flagged as wrong. Chat needs only membership, not counts,
 * examples or monthly trends. Keep this tokenizer and phrase policy separate from Words:
 * names that match a deck count here, as do the existing bracketed-gap matches.
 */
fun usedDeckKeys(deck: VocabDeck, corpus: List<Utterance>, lemmatizer: Lemmatizer): Set<String> {
    val index = DeckIndex(deck, lemmatizer)
    return buildSet {
        for (utterance in corpus.filter { it.language == deck.language }.sortedBy { it.at }) {
            for (token in WordFolding.tokens(utterance.text)) {
                if (!utterance.isCorrected(token.start, token.end)) {
                    index.match(token.text.lowercase()).entries.mapTo(this) { it.key }
                }
            }
            for ((entry, regex) in index.phrases) {
                if (regex.findAll(utterance.text).any { !utterance.isCorrected(it.range.first, it.range.last + 1) }) {
                    add(entry.key)
                }
            }
        }
    }
}

/** Fast lookup of a deck's rows, and the matching rules for one written word. */
internal class DeckIndex(private val deck: VocabDeck, private val lemmatizer: Lemmatizer) {
    /** The deck rows a word reached; [bySpelling] when no part of speech backed the match. */
    data class Match(val entries: Set<DeckEntry>, val bySpelling: Boolean)

    private val language = deck.language
    private val words = deck.entries.filterNot { it.isPhrase }
    private val byKey: Map<String, DeckEntry> = words.associateBy { it.key }
    private val byLemma: Map<String, List<DeckEntry>> = words.groupBy { it.lemma }
    private val byForm: Map<String, List<DeckEntry>> = HashMap<String, MutableList<DeckEntry>>().also { map ->
        words.forEach { entry -> entry.forms.forEach { form -> map.getOrPut(form) { ArrayList() } += entry } }
    }
    private val byStripped: Map<String, List<DeckEntry>> = byLemma.keys
        .groupBy { WordFolding.stripAccents(it) }
        .filterValues { it.size == 1 }
        .mapValues { byLemma.getValue(it.value.single()) }
    val phrases: List<Pair<DeckEntry, Regex>> = deck.entries.filter { it.isPhrase }.map { entry ->
        entry to Regex("(?<!\\p{L})" + entry.lemma.split(' ').joinToString("\\s+") { Regex.escape(it) } + "(?!\\p{L})",
            setOf(RegexOption.IGNORE_CASE))
    }

    /** Spelling matches only matter where rows carry parts of speech. */
    private val spellingCounts = deck.level == VocabLevel.Lexeme && deck.hasLexemes

    /** The deck rows [word] (lowercase) counts toward. */
    fun match(word: String): Match {
        val lexemes = lemmatizer.lexemes(word, language)
        val exact = LinkedHashSet<DeckEntry>()
        for (lexeme in lexemes) {
            if (lexeme.pos == Pos.Unknown) continue
            byKey["${lexeme.lemma}/${lexeme.pos.token}"]?.let(exact::add)
            byLemma[lexeme.lemma]?.filterTo(exact) { it.pos == null }
        }
        byForm[word]?.let(exact::addAll)
        if (exact.isNotEmpty()) return Match(exact, false)
        if (deck.formsOnly) return Match(byLemma[word].orEmpty().toSet(), false)

        val spelling = (lexemes.map { it.lemma } + word + WordFolding.irregular(word, language))
            .flatMapTo(LinkedHashSet()) { byLemma[it].orEmpty() }
        if (spelling.isNotEmpty()) return Match(spelling, spellingCounts)
        val nominal = WordFolding.nominal(word, language)
        nominal.firstNotNullOfOrNull { byLemma[it] }?.let { return Match(it.toSet(), spellingCounts) }
        WordFolding.verbal(word, language).firstNotNullOfOrNull { candidate ->
            byLemma[candidate]?.filter { it.pos == Pos.Verb.token || it.pos == null && candidate.length > 3 }?.takeIf { it.isNotEmpty() }
        }?.let { return Match(it.toSet(), spellingCounts) }
        // Accents left off ("aqui", "telefono"): only when exactly one deck lemma fits.
        (listOf(word) + nominal).firstNotNullOfOrNull { byStripped[WordFolding.stripAccents(it)] }
            ?.let { return Match(it.toSet(), spellingCounts) }
        return Match(emptySet(), false)
    }
}
