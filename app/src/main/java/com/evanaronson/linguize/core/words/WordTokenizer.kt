package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.WriterGaps
import java.text.Normalizer
import java.util.Locale

/**
 * Lab, Your words: one word the writer typed. [start] and [end] are character positions in
 * the text it came from (`[start, end)`), so it can be highlighted and checked against
 * corrected spans.
 */
data class WordToken(
    /** The word as the index counts it: lowercase, NFC, ' for apostrophes, l·l for the Catalan geminate. */
    val form: String,
    val start: Int,
    val end: Int,
    /** Exactly as written. */
    val written: String,
    /** Capitalized in the middle of a sentence (or after an elided article): most likely a name. */
    val name: Boolean,
    /** Capitalized where any word would be (the start of a sentence or line), so capitals say nothing. */
    val capitalizedAtStart: Boolean = false,
)

/**
 * Lab: splits the writer's text into words, aware of Spanish and Catalan spelling.
 *
 * - Letters with accents, ñ, ç and the Catalan l·l (also typed l.l, l•l or ŀl) stay in one word.
 * - Elisions split from their word: l'home is l' and home, d'aquí is d' and aquí, porta'l is
 *   porta and 'l.
 * - Hyphenated clitics split from their verb: digues-li is digues and li, dóna-m'ho is dóna,
 *   m' and ho. Other hyphenated words (porta-avions) stay whole.
 * - Numbers, words with digits, links, email addresses, @mentions, #hashtags, emoji and
 *   laughter (jajaja, hahaha) are left out.
 * - Capitalized words in mid-sentence are flagged as names; the index keeps them apart.
 * - Gaps the writer left in square brackets (`[pick me up]`) are not their words: skipped.
 */
object WordTokenizer {
    fun tokenize(text: String): List<WordToken> {
        val shouting = isShouting(text)
        val tokens = mutableListOf<WordToken>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            var chunkEnd = i
            while (chunkEnd < text.length && !text[chunkEnd].isWhitespace()) chunkEnd++
            if (!isSkippedChunk(text.substring(i, chunkEnd))) tokenizeChunk(text, i, chunkEnd, shouting, tokens)
            i = chunkEnd
        }
        val gaps = WriterGaps.bracketed(text)
        return if (gaps.isEmpty()) tokens else tokens.filterNot { WriterGaps.inGap(gaps, it.start, it.end) }
    }

    private fun tokenizeChunk(text: String, from: Int, to: Int, shouting: Boolean, out: MutableList<WordToken>) {
        var i = from
        while (i < to) {
            if (!isWordChar(text[i])) { i++; continue }
            val start = i
            var end = i + 1
            while (end < to) {
                val c = text[end]
                if (isWordChar(c)) { end++; continue }
                if (isConnector(text, end, start, to)) { end++; continue }
                break
            }
            if (text.substring(start, end).none { it.isDigit() }) splitRun(text, start, end, shouting, out)
            i = end
        }
    }

    /** A hyphen, apostrophe or interpunct joins two word characters. */
    private fun isConnector(text: String, at: Int, runStart: Int, chunkEnd: Int): Boolean {
        val c = text[at]
        if (at + 1 >= chunkEnd || !isWordChar(text[at + 1]) || at == runStart) return false
        val before = text[at - 1]
        if (!isWordChar(before)) return false
        return when {
            c in APOSTROPHES || c in HYPHENS -> true
            c in INTERPUNCTS -> before.lowercaseChar() == 'l' && text[at + 1].lowercaseChar() == 'l'
            else -> false
        }
    }

    private data class Piece(val start: Int, val end: Int)

    /** Splits one run of letters and connectors into elisions, clitics and words. */
    private fun splitRun(text: String, start: Int, end: Int, shouting: Boolean, out: MutableList<WordToken>) {
        // Hyphen segments.
        val segments = mutableListOf<Piece>()
        var segStart = start
        for (k in start until end) {
            if (text[k] in HYPHENS) {
                segments += Piece(segStart, k)
                segStart = k + 1
            }
        }
        segments += Piece(segStart, end)
        val first = apostrophePieces(text, segments.first())
        val rest = segments.drop(1).map { apostrophePieces(text, it) }
        val pieces = if (rest.all { segment -> segment.all { isClitic(form(text, it)) } }) {
            first + rest.flatten()
        } else {
            // A compound: everything from the last piece of the first segment stays together.
            first.dropLast(1) + Piece(first.last().start, end)
        }
        val atSentenceStart = startsSentence(text, start)
        pieces.forEachIndexed { index, piece ->
            val form = form(text, piece)
            if (form.isEmpty() || isLaughter(form)) return@forEachIndexed
            val written = text.substring(piece.start, piece.end)
            val firstLetter = written.firstOrNull { it.isLetter() }
            val capitalized = !shouting && firstLetter != null && firstLetter.isUpperCase()
            // After an elided article, only the article took the sentence's capital.
            val firstInSentence = index == 0 && atSentenceStart
            out += WordToken(
                form = form,
                start = piece.start,
                end = piece.end,
                written = written,
                name = capitalized && !firstInSentence && !isElision(form),
                capitalizedAtStart = capitalized && firstInSentence,
            )
        }
    }

    /** Splits a segment at its apostrophes: l'home is l' + home, porta'l is porta + 'l. */
    private fun apostrophePieces(text: String, segment: Piece): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var pieceStart = segment.start
        var k = segment.start
        while (k < segment.end) {
            if (text[k] in APOSTROPHES) {
                val left = text.substring(pieceStart, k).lowercase(Locale.ROOT)
                if (left in ELIDED) {
                    pieces += Piece(pieceStart, k + 1)
                    pieceStart = k + 1
                } else {
                    if (k > pieceStart) pieces += Piece(pieceStart, k)
                    pieceStart = k
                }
            }
            k++
        }
        if (segment.end > pieceStart) pieces += Piece(pieceStart, segment.end)
        return pieces
    }

    /** The counted form of `text[piece]`. */
    private fun form(text: String, piece: Piece): String = normalize(text.substring(piece.start, piece.end))

    /** Lowercase, NFC, one apostrophe and one interpunct. Public so search and tests read words the same way. */
    fun normalize(word: String): String {
        val composed = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val out = StringBuilder(composed.length + 2)
        for ((index, c) in composed.withIndex()) {
            when {
                c in APOSTROPHES -> out.append('\'')
                c == 'ŀ' -> out.append("l·")
                c in INTERPUNCTS && index > 0 && composed[index - 1] == 'l' -> out.append('·')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    fun isClitic(form: String): Boolean = form in CLITICS

    private fun isElision(form: String): Boolean = form.endsWith('\'') && form.dropLast(1) in ELIDED

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c.isDigit() ||
        Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    /** True where any word would be capitalized: the start of the text, a sentence or a line. */
    private fun startsSentence(text: String, at: Int): Boolean {
        var k = at - 1
        while (k >= 0) {
            val c = text[k]
            when {
                c == '\n' || c == '\r' || c in STARTERS -> return true
                c.isWhitespace() || c in OPENERS -> k--
                c in ENDERS -> return true
                c.isLetterOrDigit() || c == ',' -> return false
                // A bullet, emoji or other symbol opens a new thought in chat.
                c.isSurrogate() || Character.getType(c) == Character.OTHER_SYMBOL.toInt() || c in BULLETS -> return true
                else -> return false
            }
        }
        return true
    }

    private fun isSkippedChunk(chunk: String): Boolean {
        val trimmed = chunk.trim { !it.isLetterOrDigit() && it != '@' && it != '#' && it != '/' && it != ':' }
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("www.") ||
            "://" in lower || lower.startsWith("@") || lower.startsWith("#") ||
            EMAIL.matches(lower) || DOMAIN.matches(lower)
    }

    /** Mostly capital letters: capitals then say nothing about names. */
    private fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 8 && letters.count { it.isUpperCase() } * 10 >= letters.length * 7
    }

    private fun isLaughter(form: String): Boolean = LAUGHTER.matches(form)

    private const val APOSTROPHES = "'’ʼ‘"
    private const val HYPHENS = "-‐‑"
    private const val INTERPUNCTS = "·.•∙⋅"
    private const val ENDERS = ".!?…:;"
    private const val OPENERS = "\"'”‘’»([{"

    /** A question, exclamation or quotation opens with a capital wherever it stands. */
    private const val STARTERS = "¿¡“«"
    private const val BULLETS = "-–—•*>"

    /** Words that drop their vowel before another: Catalan articles, de, pronouns; qu' and que. */
    private val ELIDED = setOf("l", "d", "m", "t", "s", "n", "qu")

    /** Pronouns that follow a verb, after a hyphen (digues-li, fes-ho) or an apostrophe (porta'l). */
    private val CLITICS = setOf(
        "me", "te", "se", "nos", "vos", "os", "los", "les", "las", "li", "lo", "la", "le", "ho", "hi", "en", "ne",
        "us", "ens", "m'", "t'", "s'", "n'", "l'", "'m", "'t", "'s", "'n", "'l", "'ls", "'ns", "'hi", "'ho", "'us",
    )

    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
    private val DOMAIN = Regex("""[\p{L}\d-]+(\.[\p{L}\d-]+)*\.(com|es|cat|org|net|eu|io|info|app|dev|co|uk|fr|de|gov|edu)(/\S*)?""")
    private val LAUGHTER = Regex("""(j[aeiou]){2,}j?|(h[aeiou]){2,}h?|jaj|xd+|lol""")
}
