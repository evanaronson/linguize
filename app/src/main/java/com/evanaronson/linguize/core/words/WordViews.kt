package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.core.corpus.Pos
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/** The writer's sentence around one use, with the word at [start] until [end]. */
data class WordContext(val use: WordUse, val sentence: String, val start: Int, val end: Int, val cutBefore: Boolean, val cutAfter: Boolean)

object WordViews {
    /** Longest sentence shown whole; longer ones are cut to the words around the word. */
    const val MAX_SENTENCE = 200
    private const val AROUND = 80

    /** Lowercase without accents, for search: "aqui" finds "aquí". */
    fun fold(text: String): String =
        Normalizer.normalize(WordTokenizer.normalize(text), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** The sentence [use] stands in, from [texts]; null when its text is gone. */
    fun context(use: WordUse, texts: Map<String, com.evanaronson.linguize.core.corpus.Utterance>): WordContext? {
        val text = texts[use.utteranceId]?.text ?: return null
        if (use.end > text.length) return null
        val lastChar = if (use.end > use.start) use.end - 1 else use.start
        val ranges = Sentences.ranges(text)
        var from = ranges.lastOrNull { it.first <= use.start }?.first ?: 0
        var to = ranges.lastOrNull { it.first <= lastChar }?.let { it.last + 1 } ?: text.length
        to = maxOf(to, use.end)
        var cutBefore = false
        var cutAfter = false
        if (to - from > MAX_SENTENCE) {
            val wantFrom = maxOf(from, use.start - AROUND)
            val wantTo = minOf(to, use.end + AROUND)
            cutBefore = wantFrom > from
            cutAfter = wantTo < to
            from = if (cutBefore) wordStart(text, wantFrom, use.start) else from
            to = if (cutAfter) wordEnd(text, wantTo, use.end) else to
        }
        while (from < use.start && text[from].isWhitespace()) from++
        while (to > use.end && text[to - 1].isWhitespace()) to--
        return WordContext(use, text.substring(from, to), use.start - from, use.end - from, cutBefore, cutAfter)
    }

    private fun wordStart(text: String, at: Int, limit: Int): Int {
        var i = at
        while (i < limit && !text[i].isWhitespace()) i++
        return minOf(i, limit)
    }

    private fun wordEnd(text: String, at: Int, limit: Int): Int {
        var i = at
        while (i > limit && !text[i - 1].isWhitespace()) i--
        return maxOf(i, limit)
    }

    /**
     * CSV of every form: word, lemma, lexeme_pos, deck_rank, right, wrong, accuracy, first, last.
     * right and wrong never overlap (uses outside and inside corrected spans); accuracy is the
     * whole percent used right ([WordAccuracy]). A form that could be
     * more than one lexeme has one row per lexeme, each with the form's full counts (an
     * ambiguous form counts toward every candidate). lexeme_pos is the part-of-speech token
     * ("v", "art"), empty when unknown. deck_rank is that lexeme's rank in [coverage]'s deck (its
     * row, else its lemma's best row), empty when it isn't in the deck or no deck is chosen.
     * Dates are local, yyyy-mm-dd.
     */
    fun csv(words: LanguageWords, coverage: DeckCoverage? = null, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("word,lemma,lexeme_pos,deck_rank,right,wrong,accuracy,first,last\r\n")
        for (entry in words.words.sortedWith(compareByDescending<WordEntry> { it.uses }.thenBy { it.form })) {
            for (lexeme in entry.lexemes.sortedWith(compareBy({ it.lemma }, { it.pos.ordinal }))) {
                val fields = listOf(
                    entry.form,
                    lexeme.lemma,
                    lexeme.pos.takeIf { it != Pos.Unknown }?.token.orEmpty(),
                    (coverage?.rankOf(lexeme) ?: coverage?.rankOfForm(entry.form))?.toString().orEmpty(),
                    entry.right.toString(),
                    entry.wrong.toString(),
                    entry.accuracy?.toString().orEmpty(),
                    day(entry.firstAt, zone),
                    day(entry.lastAt, zone),
                )
                append(fields.joinToString(",", transform = ::csvField)).append("\r\n")
            }
        }
    }

    private fun day(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    private fun csvField(value: String): String {
        // A leading =, +, - or @ would run as a formula in a spreadsheet.
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
