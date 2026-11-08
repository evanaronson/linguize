package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Pos

/** A group of "Words to try next": a [Pos] token, [OTHER], or [ALL] for a deck without parts of speech. */
data class NextWords(val group: String, val entries: List<DeckEntry>) {
    companion object {
        const val OTHER = "other"
        const val ALL = "all"
    }
}

/** Lab, Vocabulary gaps: the gaps worth filling first, from the top of the deck down. */
object VocabSuggestions {
    private val GROUPS = listOf(Pos.Verb, Pos.Noun, Pos.Adjective, Pos.Adverb).map { it.token }

    /** The highest-ranked unused rows: verbs, nouns, adjectives, adverbs, then the rest. */
    fun next(deck: VocabDeck, report: VocabReport, perGroup: Int = 5): List<NextWords> {
        val unused = deck.entries.filter { it.key !in report.used }
        if (!deck.hasLexemes) {
            return listOf(NextWords(NextWords.ALL, unused.take(perGroup * 2))).filter { it.entries.isNotEmpty() }
        }
        val groups = GROUPS.map { pos -> NextWords(pos, unused.filter { it.pos == pos }.take(perGroup)) }
        val other = NextWords(NextWords.OTHER, unused.filter { it.pos !in GROUPS }.take(perGroup))
        return (groups + other).filter { it.entries.isNotEmpty() }
    }

    /**
     * A few words to bring into a conversation: the top unused verbs, nouns and adjectives,
     * taken in turn so the set is varied, in deck order, each lemma once.
     */
    fun targets(deck: VocabDeck, report: VocabReport, count: Int = 5): List<DeckEntry> {
        val unused = deck.entries.filter { it.key !in report.used && !it.isPhrase }.distinctBy { it.lemma }
        val content = unused.filter { it.pos in GROUPS }.ifEmpty { unused }
        val byGroup = content.groupBy { it.pos }.values.map { it.iterator() }
        val picked = ArrayList<DeckEntry>()
        while (picked.size < count && byGroup.any { it.hasNext() }) {
            for (group in byGroup) if (group.hasNext() && picked.size < count) picked += group.next()
        }
        return picked.sortedBy { it.rank }
    }
}
