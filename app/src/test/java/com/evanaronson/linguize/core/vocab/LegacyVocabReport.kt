package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import org.junit.Assert.assertEquals
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class Example(val utterance: String, val start: Int, val end: Int, val at: Long)

data class WordUse(val count: Int, val lastAt: Long, val examples: List<Example>, val bySpelling: Boolean = false)

data class MonthPoint(val month: String, val topUsed: Int, val distinct: Int, val rarer: Int, val utterances: Int) {
    val rarerPercent: Int get() = if (distinct == 0) 0 else rarer * 100 / distinct
}

data class VocabReport(
    val fingerprint: String,
    val deckId: String,
    val language: String,
    val level: VocabLevel = VocabLevel.Lexeme,
    val utterances: Int,
    val words: Int,
    val tried: Int,
    val bySpelling: Int = 0,
    val used: Map<String, WordUse>,
    val milestones: List<Milestone>,
    val distinct: Int,
    val rarer: Int,
    val trendTop: Int,
    val trend: List<MonthPoint>,
) {
    val headline: Milestone?
        get() = milestones.lastOrNull { it.used * 2 >= it.top } ?: milestones.firstOrNull()

    companion object {
        const val RARE_RANK = 2_000
        const val TREND_TOP = 1_000
        const val MAX_EXAMPLES = 5
        const val MAX_MONTHS = 24
    }
}

/** Pre-cleanup report oracle: retain every report assertion and check production membership on each fixture. */
class VocabAnalyser(private val lemmatizer: Lemmatizer, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun analyse(deck: VocabDeck, corpus: List<Utterance>): VocabReport {
        val utterances = corpus.filter { it.language == deck.language }.sortedBy { it.at }
        val index = DeckIndex(deck, lemmatizer)
        val ranks = ReportRanks(deck)
        val uses = HashMap<String, MutableList<Example>>()
        val spelled = HashSet<String>()
        val monthKeys = LinkedHashMap<YearMonth, MutableSet<String>>()
        val monthDeck = LinkedHashMap<YearMonth, MutableSet<String>>()
        val monthUtterances = HashMap<YearMonth, Int>()
        val allKeys = HashSet<String>()
        var words = 0
        var tried = 0
        var bySpelling = 0
        for (utterance in utterances) {
            val month = YearMonth.from(Instant.ofEpochMilli(utterance.at).atZone(zone))
            monthUtterances.merge(month, 1, Int::plus)
            val keys = monthKeys.getOrPut(month) { HashSet() }
            val inDeck = monthDeck.getOrPut(month) { HashSet() }
            fun use(entry: DeckEntry, start: Int, end: Int, spelling: Boolean) {
                uses.getOrPut(entry.key) { ArrayList() } += Example(utterance.id, start, end, utterance.at)
                if (spelling) spelled += entry.key
                inDeck += entry.key
                keys += DECK + entry.lemma
            }
            for (token in WordFolding.tokens(utterance.text)) {
                if (utterance.isCorrected(token.start, token.end)) {
                    tried++
                    continue
                }
                val word = token.text.lowercase()
                val match = index.match(word)
                if (match.entries.isNotEmpty()) {
                    words++
                    if (match.bySpelling) bySpelling++
                    match.entries.forEach { use(it, token.start, token.end, match.bySpelling) }
                    continue
                }
                // A capitalised word mid-sentence that no deck knows is most likely a name.
                if (word.length < 2 || (token.text.first().isUpperCase() && !token.sentenceStart)) continue
                words++
                keys += OTHER + (lemmatizer.lemmas(word, deck.language).minOrNull() ?: word)
            }
            for ((entry, regex) in index.phrases) {
                regex.findAll(utterance.text).forEach { found ->
                    val end = found.range.last + 1
                    if (!utterance.isCorrected(found.range.first, end)) use(entry, found.range.first, end, false)
                }
            }
            allKeys += keys
        }

        val used = uses.mapValues { (key, examples) ->
            WordUse(
                count = examples.size,
                lastAt = examples.maxOf { it.at },
                examples = examples.sortedByDescending { it.at }.take(VocabReport.MAX_EXAMPLES),
                bySpelling = key in spelled,
            )
        }
        val trendTop = minOf(VocabReport.TREND_TOP, deck.size)
        val report = VocabReport(
            fingerprint = fingerprint(deck, corpus, lemmatizer),
            deckId = deck.id,
            language = deck.language,
            level = deck.level,
            utterances = utterances.size,
            words = words,
            tried = tried,
            bySpelling = bySpelling,
            used = used,
            milestones = Milestone.tops(deck.size).map { top -> Milestone(top, deck.entries.take(top).count { it.key in used }) },
            distinct = allKeys.size,
            rarer = allKeys.count { ranks.isRare(it) },
            trendTop = trendTop,
            trend = trend(monthKeys, monthDeck, monthUtterances, ranks, trendTop),
        )
        assertEquals("Legacy report and production Chat membership", report.used.keys, usedDeckKeys(deck, corpus, lemmatizer))
        return report
    }

    private fun trend(
        monthKeys: Map<YearMonth, Set<String>>,
        monthDeck: Map<YearMonth, Set<String>>,
        monthUtterances: Map<YearMonth, Int>,
        index: ReportRanks,
        trendTop: Int,
    ): List<MonthPoint> {
        val first = monthKeys.keys.minOrNull() ?: return emptyList()
        val last = monthKeys.keys.max()
        val seenTop = HashSet<String>()
        val points = ArrayList<MonthPoint>()
        var month = first
        while (month <= last) {
            monthDeck[month].orEmpty().filterTo(seenTop) { index.rank(it) <= trendTop }
            val keys = monthKeys[month].orEmpty()
            points += MonthPoint(month.toString(), seenTop.size, keys.size, keys.count { index.isRare(it) }, monthUtterances[month] ?: 0)
            month = month.plusMonths(1)
        }
        return points.takeLast(VocabReport.MAX_MONTHS)
    }

    companion object {
        const val VERSION = 2
        private const val DECK = "d:"
        private const val OTHER = "x:"

        fun fingerprint(deck: VocabDeck, corpus: List<Utterance>, lemmatizer: Lemmatizer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                digest.update(value.toByteArray())
                digest.update(0)
            }
            add("v$VERSION")
            add(lemmatizer.javaClass.name)
            add(deck.id)
            add(deck.language)
            add(deck.level.name)
            deck.entries.forEach { add("${it.rank}:${it.key}:${it.forms.joinToString(";")}") }
            corpus.filter { it.language == deck.language }.sortedBy { it.id }.forEach { utterance ->
                add(utterance.id)
                add(utterance.at.toString())
                add(utterance.text)
                add(utterance.corrected.joinToString(",") { "${it.start}-${it.end}" })
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private class ReportRanks(deck: VocabDeck) {
    private val ranks = deck.entries.associate { it.key to it.rank }
    private val lemmaRanks = deck.entries.groupBy { it.lemma }.mapValues { (_, rows) -> rows.minOf { it.rank } }
    fun rank(key: String): Int = ranks[key] ?: Int.MAX_VALUE
    fun isRare(key: String): Boolean =
        !key.startsWith("d:") || (lemmaRanks[key.removePrefix("d:")] ?: Int.MAX_VALUE) > VocabReport.RARE_RANK
}
