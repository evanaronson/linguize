package com.evanaronson.linguize.core

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Aligns two versions of a text token by token (words, single punctuation
 * marks, runs of spaces) with the fewest changes, preferring to pair a word
 * with its own corrected spelling ("estas" with "Estás").
 */
internal object Alignment {
    enum class Type { Word, Space, Punct }

    data class Token(val text: String, val start: Int, val type: Type) {
        val end get() = start + text.length

        /** Lowercase without accents, for pairing a word with its corrected spelling. */
        val folded: String = if (type == Type.Word) fold(text) else text

        /**
         * The text in composed form (NFC), so a word typed with a separate accent mark
         * ("e" + "◌́", as text pasted from macOS often has) equals the same word written with
         * "é". Only for comparing: positions and [text] stay as the text has them.
         */
        val normal: String = nfc(text)
    }

    /** One step of the alignment: a token kept, replaced, removed (b null) or added (a null). */
    data class Op(val a: Token?, val b: Token?) {
        val isMatch get() = a != null && b != null && a.normal == b.normal
        val token get() = a ?: b!!
        val isPunct get() = a?.type == Type.Punct || b?.type == Type.Punct
    }

    /**
     * The cheapest alignment, walked back from the ends of both texts preferring a pair,
     * then a removal. The costs it walks back over would take a table of (n + 1) × (m + 1)
     * cells, too much memory for long texts, so only what the walk needs is kept: the
     * tokens both texts share at their start and end need no table, and of the rest only
     * every few rows are kept and the rows between them worked out again as the walk
     * reaches them.
     */
    fun align(original: String, target: String): List<Op> {
        val a = tokenize(original)
        val b = tokenize(target)
        // Shared tokens at the end pair first: equal tokens at the end always pair on a
        // cheapest path, and the walk prefers a pair.
        var suffix = 0
        while (suffix < minOf(a.size, b.size) && a[a.size - 1 - suffix].normal == b[b.size - 1 - suffix].normal) suffix++
        val n = a.size - suffix
        val m = b.size - suffix
        // At the start the walk can leave the shared tokens ("a a" against "a" pairs the second
        // "a"), so it goes on into them, where costs need no table: aligning a text with one that
        // starts with all of it only costs adding the rest.
        var prefix = 0
        while (prefix < minOf(n, m) && a[prefix].normal == b[prefix].normal) prefix++
        val gapsA = IntArray(n + 1).also { for (k in 0 until n) it[k + 1] = it[k] + gap(a[k]) }
        val gapsB = IntArray(m + 1).also { for (k in 0 until m) it[k + 1] = it[k] + gap(b[k]) }
        fun shared(i: Int, j: Int) = if (j >= i) gapsB[j] - gapsB[i] else gapsA[i] - gapsA[j]

        // The table covers i and j from prefix on; row r holds i = prefix + r.
        val rows = n - prefix
        val columns = m - prefix
        fun fill(row: IntArray, above: IntArray, r: Int) {
            val i = prefix + r
            row[0] = shared(i, prefix)
            for (c in 1..columns) {
                val j = prefix + c
                var best = minOf(above[c] + gap(a[i - 1]), row[c - 1] + gap(b[j - 1]))
                substitution(a[i - 1], b[j - 1])?.let { best = minOf(best, above[c - 1] + it) }
                row[c] = best
            }
        }
        val step = maxOf(1, Math.ceil(Math.sqrt(rows + 1.0)).toInt())
        val kept = arrayOfNulls<IntArray>(rows / step + 1)
        var above = IntArray(columns + 1) { shared(prefix, prefix + it) }
        var row = IntArray(columns + 1)
        kept[0] = above.copyOf()
        for (r in 1..rows) {
            fill(row, above, r)
            if (r % step == 0) kept[r / step] = row.copyOf()
            above = row.also { row = above }
        }
        // Rows first..first + step, worked out again from the kept row at first.
        val block = Array(step + 1) { IntArray(0) }
        var first = -1
        fun row(r: Int): IntArray {
            if (first < 0 || r < first || r > first + step) {
                first = (if (r == 0) 0 else (r - 1) / step) * step
                block[0] = kept[first / step]!!
                for (k in 1..minOf(step, rows - first)) {
                    if (block[k].size != columns + 1) block[k] = IntArray(columns + 1)
                    fill(block[k], block[k - 1], first + k)
                }
            }
            return block[r - first]
        }
        fun cost(i: Int, j: Int): Int =
            if (i >= prefix && j >= prefix) row(i - prefix)[j - prefix] else shared(i, j)

        val ops = ArrayDeque<Op>()
        for (k in 1..suffix) ops.addFirst(Op(a[a.size - k], b[b.size - k]))
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val sub = if (i > 0 && j > 0) substitution(a[i - 1], b[j - 1]) else null
            when {
                sub != null && cost(i, j) == cost(i - 1, j - 1) + sub -> ops.addFirst(Op(a[--i], b[--j]))
                i > 0 && cost(i, j) == cost(i - 1, j) + gap(a[i - 1]) -> ops.addFirst(Op(a[--i], null))
                else -> ops.addFirst(Op(null, b[--j]))
            }
        }
        return ops
    }

    /** Words, runs of spaces and single punctuation marks, never splitting a character such as an emoji. */
    private fun tokenize(text: String): List<Token> {
        val clusters = characters(text)
        val types = clusters.map { typeOf(text.codePointAt(it.first)) }.toMutableList()
        // A hyphen or middle dot between letters belongs to the word: "dir-ho", "col·legi".
        for (k in 1 until clusters.lastIndex) {
            val cluster = clusters[k]
            if (cluster.last == cluster.first && text[cluster.first] in JOINERS &&
                types[k - 1] == Type.Word && types[k + 1] == Type.Word
            ) {
                types[k] = Type.Word
            }
        }
        val tokens = mutableListOf<Token>()
        var k = 0
        while (k < clusters.size) {
            val type = types[k]
            var next = k + 1
            if (type != Type.Punct) while (next < clusters.size && types[next] == type) next++
            tokens += Token(text.substring(clusters[k].first, clusters[next - 1].last + 1), clusters[k].first, type)
            k = next
        }
        return tokens
    }

    /** The user-perceived characters of [text], as index ranges. */
    private fun characters(text: String): List<IntRange> {
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        val clusters = mutableListOf<IntRange>()
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            clusters += start until end
            start = end
            end = boundaries.next()
        }
        return clusters
    }

    private fun typeOf(codePoint: Int) = when {
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> Type.Space
        Character.isLetterOrDigit(codePoint) || codePoint == '\''.code || codePoint == '’'.code ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> Type.Word
        else -> Type.Punct
    }

    private fun gap(t: Token) = if (t.type == Type.Space) 1 else 2

    /** Cost of pairing [x] with [y], or null when they can't pair (a word with a comma). */
    private fun substitution(x: Token, y: Token): Int? = when {
        x.normal == y.normal -> 0
        x.type != y.type -> null
        x.type == Type.Space -> 1
        x.type == Type.Word && x.folded == y.folded -> 1
        else -> 2
    }

    /** Whether [x] and [y] are the same text, written with the same or different but equivalent characters ("é" or "e" + "◌́"). */
    fun same(x: String, y: String) = x == y || nfc(x) == nfc(y)

    private fun nfc(s: String) = if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private val JOINERS = setOf('-', '·', '‐')
}
