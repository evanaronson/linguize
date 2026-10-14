package com.evanaronson.linguize.core

import java.text.Normalizer

/**
 * Preserves uniquely shared whole words, then aligns complete Unicode characters
 * between them. Unchanged words cannot be borrowed to manufacture other repairs.
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
     * Exact whole words shared once by each text are anchors when their order agrees.
     * Words participating in a crossing stay inside an unanchored run; unaffected
     * anchors remain intact. Repeated words keep the character alignment's last-match tie break.
     */
    fun align(original: String, target: String): List<Op> {
        val a = characterTokens(original)
        val b = characterTokens(target)
        val sourceUnits = units(original)
        val targetUnits = units(target)
        val anchors = wordAnchors(sourceUnits, targetUnits).ordered
        val budget = WorkBudget()
        if (anchors.isEmpty())
            return completeSeparators(align(a, b, budget), sourceUnits, targetUnits)

        val result = mutableListOf<Op>()
        var from = 0
        var to = 0
        fun appendUntil(sourceEnd: Int, targetEnd: Int) {
            var nextFrom = from
            var nextTo = to
            while (nextFrom < a.size && a[nextFrom].start < sourceEnd) nextFrom++
            while (nextTo < b.size && b[nextTo].start < targetEnd) nextTo++
            result += align(a.subList(from, nextFrom), b.subList(to, nextTo), budget)
            from = nextFrom
            to = nextTo
        }
        for ((left, right) in anchors) {
            appendUntil(left.start, right.start)
            appendUntil(left.end, right.end)
        }
        appendUntil(original.length, target.length)
        return completeSeparators(result, sourceUnits, targetUnits)
    }

    data class WordAnchors(val ordered: List<Pair<Token, Token>>, val crossed: List<Pair<Token, Token>>)

    /** Share one partition between alignment and the edit gate: every inversion participant is crossed. */
    fun wordAnchors(sourceUnits: List<Token>, targetUnits: List<Token>): WordAnchors {
        val words = sharedWords(sourceUnits, targetUnits)
        val followingMin = IntArray(words.size + 1) { Int.MAX_VALUE }
        for (index in words.lastIndex downTo 0)
            followingMin[index] = minOf(words[index].second.start, followingMin[index + 1])
        val ordered = mutableListOf<Pair<Token, Token>>()
        val crossed = mutableListOf<Pair<Token, Token>>()
        var precedingMax = Int.MIN_VALUE
        for ((index, pair) in words.withIndex()) {
            val position = pair.second.start
            if (position > precedingMax && position < followingMin[index + 1]) ordered += pair else crossed += pair
            precedingMax = maxOf(precedingMax, position)
        }
        return WordAnchors(ordered, crossed)
    }

    /** Unique NFC-equal whole-word pairs in source order; target order may cross. */
    private fun sharedWords(sourceUnits: List<Token>, targetUnits: List<Token>): List<Pair<Token, Token>> {
        val source = sourceUnits.filter { it.type == Type.Word }.groupBy { it.normal }
        val target = targetUnits.filter { it.type == Type.Word }.groupBy { it.normal }
        return source.values.mapNotNull { words ->
            val word = words.singleOrNull() ?: return@mapNotNull null
            target[word.normal]?.singleOrNull()?.let { word to it }
        }
    }

    /**
     * Character cost can pair the space owned by a removed word with punctuation:
     * " y " -> ". " pairs the leading space with the dot and removes only "y".
     * Keep that proven whole-word deletion/insertion together with its separator.
     * Other punctuation/space pairs, including those beside spelling repairs, stay paired.
     */
    private fun completeSeparators(ops: List<Op>, source: List<Token>, target: List<Token>): List<Op> {
        fun cross(op: Op) = op.a?.type == Type.Space && op.b?.type == Type.Punct ||
            op.a?.type == Type.Punct && op.b?.type == Type.Space
        if (ops.none(::cross)) return ops

        fun owners(units: List<Token>, original: Boolean): Map<Int, Boolean> {
            val absent = BooleanArray(units.lastOrNull()?.end ?: 0)
            for (op in ops) {
                val token = if (original) op.a else op.b
                val other = if (original) op.b else op.a
                if (token != null && other == null) for (at in token.start until token.end) absent[at] = true
            }
            val wholeWords = units.map { unit ->
                unit.type == Type.Word && (unit.start until unit.end).all { absent[it] }
            }
            val result = mutableMapOf<Int, Boolean>()
            for ((index, unit) in units.withIndex()) {
                if (unit.type != Type.Space) continue
                val before = wholeWords.getOrNull(index - 1) == true
                val after = wholeWords.getOrNull(index + 1) == true
                // Only a single, established owner determines which side stays joined.
                if (before == after) continue
                for (at in unit.start until unit.end) result[at] = after
            }
            return result
        }
        val removed = owners(source, true)
        val added = owners(target, false)
        return ops.flatMap { op ->
            if (!cross(op)) return@flatMap listOf(op)
            val spaceInSource = op.a!!.type == Type.Space
            val wordAfter = if (spaceInSource) removed[op.a.start] else added[op.b!!.start]
            if (wordAfter == null) return@flatMap listOf(op)
            val remove = Op(op.a, null)
            val insert = Op(null, op.b)
            // Leading space stays before its word; trailing space stays after it.
            if (spaceInSource == wordAfter) listOf(insert, remove) else listOf(remove, insert)
        }
    }

    /** Whole lexical and whitespace units used to project character changes without borrowing neighbours. */
    fun units(text: String): List<Token> = tokenize(text)

    private fun characterTokens(text: String) = characters(text).map {
        Token(text.substring(it), it.first, typeOf(text.codePointAt(it.first)))
    }

    /**
     * The cheapest alignment within a run, walked back from its ends preferring a pair,
     * then a removal. The costs it walks back over would take a table of (n + 1) × (m + 1)
     * cells, too much memory for long texts, so only what the walk needs is kept: the
     * tokens both texts share at their start and end need no table, and of the rest only
     * every few rows are kept and the rows between them worked out again as the walk
     * reaches them. A simple valid edit script bounds the cost: a cheaper path cannot
     * stray farther from the diagonal than that bound, so light corrections need only
     * a narrow band, even when changes at both ends surround a long unchanged passage.
     */
    private fun align(a: List<Token>, b: List<Token>, budget: WorkBudget): List<Op> {
        // Shared tokens at the end pair first: equal tokens at the end always pair on a
        // cheapest path, and the walk prefers a pair.
        var suffix = 0
        while (suffix < minOf(a.size, b.size) && a[a.size - 1 - suffix].normal == b[b.size - 1 - suffix].normal) suffix++
        val n = a.size - suffix
        val m = b.size - suffix
        if (n == 0 && m == 0) return a.indices.map { Op(a[it], b[it]) }
        // At the start the walk can leave the shared tokens ("a a" against "a" pairs the second
        // "a"), so it goes on into them, where costs need no table: aligning a text with one that
        // starts with all of it only costs adding the rest.
        var prefix = 0
        while (prefix < minOf(n, m) && a[prefix].normal == b[prefix].normal) prefix++
        val gapsA = IntArray(n + 1).also { for (k in 0 until n) it[k + 1] = it[k] + gap(a[k]) }
        val gapsB = IntArray(m + 1).also { for (k in 0 until m) it[k + 1] = it[k] + gap(b[k]) }
        fun shared(i: Int, j: Int) = if (j >= i) gapsB[j] - gapsB[i] else gapsA[i] - gapsA[j]

        // Pair tokens at the same positions, then add/remove the tail. This may not be
        // cheapest, but it is a valid path. Every step away from the diagonal costs at
        // least one, so no optimal path can leave the band it defines.
        val paired = minOf(n, m)
        var bound = gapsA[n] - gapsA[paired] + gapsB[m] - gapsB[paired]
        for (k in prefix until paired) bound += substitution(a[k], b[k]) ?: (gap(a[k]) + gap(b[k]))

        // The table covers i and j from prefix on; row r holds i = prefix + r.
        val rows = n - prefix
        val columns = m - prefix
        // Model output is untrusted. Bound even a completely rewritten answer before
        // allocating its table; checkpoints recompute at most one additional full pass.
        val cells = rows.toLong() * minOf(columns + 1L, 2L * bound + 1)
        budget.reserve(cells)
        fun fill(row: Row, above: Row, r: Int) {
            val i = prefix + r
            row.reset(maxOf(0, r - bound), minOf(columns, r + bound) + 1)
            if (row.first == 0) row[0] = shared(i, prefix)
            for (c in maxOf(1, row.first) until row.end) {
                val j = prefix + c
                var best = minOf(above[c] + gap(a[i - 1]), row[c - 1] + gap(b[j - 1]))
                substitution(a[i - 1], b[j - 1])?.let { best = minOf(best, above[c - 1] + it) }
                row[c] = best
            }
        }
        val step = maxOf(1, Math.ceil(Math.sqrt(rows + 1.0)).toInt())
        val kept = arrayOfNulls<Row>(rows / step + 1)
        var above = Row().apply {
            reset(0, minOf(columns, bound) + 1)
            for (c in first until end) this[c] = shared(prefix, prefix + c)
        }
        var row = Row()
        kept[0] = above.copy()
        for (r in 1..rows) {
            fill(row, above, r)
            if (r % step == 0) kept[r / step] = row.copy()
            above = row.also { row = above }
        }
        // Rows first..first + step, worked out again from the kept row at first.
        val block = Array(step + 1) { Row() }
        var first = -1
        fun row(r: Int): Row {
            if (first < 0 || r < first || r > first + step) {
                first = (if (r == 0) 0 else (r - 1) / step) * step
                block[0] = kept[first / step]!!
                for (k in 1..minOf(step, rows - first)) {
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

    /** One request budget, shared by every run rather than reset at each anchor. */
    private class WorkBudget {
        private var remaining = MAX_CELLS

        fun reserve(cells: Long) {
            require(cells <= remaining) { "Text alignment exceeds the work limit" }
            remaining -= cells
        }
    }

    /** One reusable band of a row; cells outside it cannot be on a cheapest path. */
    private class Row {
        var first = 0
            private set
        var end = 0
            private set
        private var cells = IntArray(0)

        fun reset(first: Int, end: Int) {
            this.first = first
            this.end = end
            val size = end - first
            if (cells.size < size) cells = IntArray(size)
        }

        operator fun get(column: Int): Int = if (column in first until end) cells[column - first] else Int.MAX_VALUE / 4

        operator fun set(column: Int, cost: Int) {
            cells[column - first] = cost
        }

        fun copy() = Row().also { copy ->
            copy.reset(first, end)
            cells.copyInto(copy.cells, endIndex = end - first)
        }
    }

    /** Words, numbers, runs of spaces and single punctuation marks, never splitting a character such as an emoji. */
    private fun tokenize(text: String): List<Token> {
        val clusters = characters(text)
        val types = clusters.map { typeOf(text.codePointAt(it.first)) }.toMutableList()
        // Decimal/time separators inside a number belong to that complete number.
        // Include damaged runs ("2,:5"): deleting one separator and replacing the
        // other must not become independently acceptable fragments of the repair.
        var separator = 1
        while (separator < clusters.lastIndex) {
            var end = separator
            while (end < clusters.size && clusters[end].first == clusters[end].last &&
                text[clusters[end].first] in NUMBER_SEPARATORS) end++
            if (end > separator && end < clusters.size &&
                Character.isDigit(text.codePointAt(clusters[separator - 1].first)) &&
                Character.isDigit(text.codePointAt(clusters[end].first))) {
                for (index in separator until end) types[index] = Type.Word
            }
            separator = maxOf(separator + 1, end)
        }
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

    /** Extended grapheme clusters keep combining marks, CRLF and joined emoji together. */
    fun characters(text: String): List<IntRange> = grapheme.findAll(text).map { it.range }.toList()

    private val grapheme = Regex("\\X")

    private fun typeOf(codePoint: Int) = when {
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85 -> Type.Space
        Character.isLetterOrDigit(codePoint) || codePoint == '\''.code || codePoint == '’'.code ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> Type.Word
        else -> Type.Punct
    }

    private fun gap(t: Token) = if (t.type == Type.Space) 1 else 2

    /** Cost of pairing [x] with [y], or null when they can't pair (a word with a separator). */
    private fun substitution(x: Token, y: Token): Int? = when {
        x.normal == y.normal -> 0
        // A misplaced separator is one replacement, not an insertion plus a
        // deletion: "el:llibre" -> "el llibre" must keep the colon and space paired.
        x.type != Type.Word && y.type != Type.Word && x.type != y.type -> 2
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
    private val NUMBER_SEPARATORS = setOf('.', ',', ':')

    /** Per pass over all runs; leaves room for every pair in a 3,000-character check. */
    private const val MAX_CELLS = 20_000_000L
}
