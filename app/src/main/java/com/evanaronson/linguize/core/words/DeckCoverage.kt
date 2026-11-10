package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.DeckIndex
import com.evanaronson.linguize.core.vocab.Milestone
import com.evanaronson.linguize.core.vocab.VocabDeck

/**
 * The writer's use of one deck row: every use of every form that reached it ([forms]). A form
 * that reaches several rows (an ambiguous form) counts toward each. [bySpelling] when some form
 * reached it by spelling only, without a part of speech.
 */
data class DeckUse(
    val entry: DeckEntry,
    val forms: List<String>,
    val bySpelling: Boolean,
    override val right: Int,
    override val tried: Int,
    override val firstAt: Long,
    override val lastAt: Long,
    override val firstRightAt: Long?,
    override val messages: Int,
    override val recent: List<WordUse>,
) : WordCounts {
    override val key: String get() = entry.key
    override val label: String get() = entry.lemma
}

/**
 * One deck measured against the word index of its language: which rows the writer has used
 * right ([used]), coverage of the top groups ([milestones]), and the deck rows each form
 * reached. Built in one pass over the index's forms, not over the corpus again.
 */
data class DeckCoverage(
    val deck: VocabDeck,
    /** [DeckEntry.key] to the writer's use. Rows never reached are absent. */
    val uses: Map<String, DeckUse>,
    /** Form to the deck rows it reached. Forms that reached none are absent: they're not in the list. */
    val formRows: Map<String, List<DeckEntry>>,
    /** Forms that reached the deck by spelling only. */
    val bySpelling: Int,
    val milestones: List<Milestone>,
    /** The top group the chart follows: the top 1,000, or the whole deck if smaller. */
    val trendTop: Int,
) {
    /** Rows used right at least once. */
    val used: Set<String> get() = uses.filterValues { it.known }.keys

    /** The largest group used at least half of, or the smallest when none is. */
    val headline: Milestone? get() = milestones.lastOrNull { it.used * 2 >= it.top } ?: milestones.firstOrNull()

    private val byKey: Map<String, DeckEntry> by lazy { deck.entries.associateBy { it.key } }
    private val lemmaRank: Map<String, Int> by lazy { deck.entries.groupBy { it.lemma }.mapValues { (_, rows) -> rows.minOf { it.rank } } }

    fun entry(key: String): DeckEntry? = byKey[key]

    /** The best rank among the rows [form] reached; null when it's not in the list. */
    fun rankOfForm(form: String): Int? = formRows[form]?.minOfOrNull { it.rank }

    /** The rank of [lexeme]'s row, else of its lemma's best row; null when the deck has neither. */
    fun rankOf(lexeme: Lexeme): Int? =
        byKey[if (lexeme.pos == Pos.Unknown) lexeme.lemma else "${lexeme.lemma}/${lexeme.pos.token}"]?.rank ?: lemmaRank[lexeme.lemma]

    companion object {
        /**
         * Measures [deck] against [words]. Each written form goes through the deck's matching
         * rules ([DeckIndex]: lexemes first, then the deck's own forms, then spelling); multi-word
         * rows ("a menudo") are found in the writer's texts. Uses inside corrected spans count as
         * tried and never as used.
         */
        fun build(words: LanguageWords, deck: VocabDeck, lemmatizer: Lemmatizer): DeckCoverage {
            val index = DeckIndex(deck, lemmatizer)
            val rowUses = HashMap<String, MutableList<WordUse>>()
            val rowForms = HashMap<String, LinkedHashSet<String>>()
            val spelled = HashSet<String>()
            val formRows = HashMap<String, List<DeckEntry>>()
            var bySpelling = 0
            for ((form, uses) in words.uses) {
                val match = index.match(form)
                if (match.entries.isEmpty()) continue
                formRows[form] = match.entries.sortedBy { it.rank }
                if (match.bySpelling) bySpelling++
                for (entry in match.entries) {
                    rowUses.getOrPut(entry.key) { ArrayList() } += uses
                    rowForms.getOrPut(entry.key) { LinkedHashSet() } += form
                    if (match.bySpelling) spelled += entry.key
                }
            }
            for ((entry, regex) in index.phrases) {
                for (utterance in words.texts.values) {
                    regex.findAll(utterance.text).forEach { found ->
                        val start = found.range.first
                        val end = found.range.last + 1
                        rowUses.getOrPut(entry.key) { ArrayList() } +=
                            WordUse(utterance.id, utterance.source, utterance.at, start, end, utterance.isCorrected(start, end))
                    }
                }
            }
            val entries = deck.entries.associateBy { it.key }
            val uses = rowUses.mapNotNull { (key, list) ->
                val entry = entries[key] ?: return@mapNotNull null
                val counts = UseCounts.of(list)
                key to DeckUse(
                    entry = entry, forms = rowForms[key].orEmpty().toList(), bySpelling = key in spelled,
                    right = counts.right, tried = counts.tried, firstAt = counts.firstAt, lastAt = counts.lastAt,
                    firstRightAt = counts.firstRightAt, messages = counts.messages, recent = counts.recent,
                )
            }.toMap()
            val used = uses.filterValues { it.known }.keys
            return DeckCoverage(
                deck = deck,
                uses = uses,
                formRows = formRows,
                bySpelling = bySpelling,
                milestones = Milestone.tops(deck.size).map { top -> Milestone(top, deck.entries.take(top).count { it.key in used }) },
                trendTop = minOf(1_000, deck.size),
            )
        }
    }
}
