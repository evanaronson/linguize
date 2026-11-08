package com.evanaronson.linguize.core.lab

/**
 * Fill the gaps: a message in the target language with the writer's own-language words
 * left where they got stuck. The writer's words are never rewritten; only the gaps are
 * replaced, and the result is assembled here from the source, not taken from the model.
 */

/** A gap the writer marked with square brackets. [start] and [end] include the brackets. */
data class MarkedGap(val start: Int, val end: Int, val phrase: String)

/** Why a message looks like it has gaps to fill. */
sealed interface GapHint {
    /** The writer marked [count] gaps with square brackets. */
    data class Marked(val count: Int) : GapHint

    /** Some words look like English mixed into the message. */
    data object Mixed : GapHint
}

object Gaps {
    /** Longest phrase in one pair of brackets; longer is more likely a note than a gap. */
    const val MAX_PHRASE = 200

    private val BRACKETED = Regex("""\[([^\[\]\n]{1,$MAX_PHRASE})]""")
    private val WORD = Regex("""[\p{L}']+""")

    /** Square-bracketed gaps, in order. Empty brackets are not gaps. */
    fun marked(text: String): List<MarkedGap> = BRACKETED.findAll(text).mapNotNull { match ->
        val phrase = match.groupValues[1].trim()
        if (phrase.isEmpty()) null else MarkedGap(match.range.first, match.range.last + 1, phrase)
    }.toList()

    /**
     * Whether to offer to fill gaps in [text]. Brackets are an explicit signal. Without
     * them, a few words that exist in English but not in Spanish or Catalan suggest the
     * writer fell back to English mid-sentence; a message mostly in English is not
     * offered, since that is a translation, not a gap.
     */
    fun hint(text: String): GapHint? {
        val marked = marked(text)
        if (marked.isNotEmpty()) return GapHint.Marked(marked.size)
        val words = WORD.findAll(text).map { it.value.lowercase().trim('\'') }.filter { it.isNotEmpty() }.toList()
        if (words.size < 3) return null
        val english = words.count { it in ENGLISH_ONLY }
        return if (english >= 2 && english * 2 < words.size) GapHint.Mixed else null
    }

    /**
     * Common English words that are not also everyday Spanish or Catalan words (so not
     * "me", "he", "has", "on", "come", "a"), and not loanwords people write in Spanish or Catalan
     * ("email", "online", "weekend").
     */
    private val ENGLISH_ONLY = setOf(
        "the", "and", "with", "you", "your", "my", "our", "their", "they", "we", "she", "him", "her", "them",
        "it", "it's", "i'm", "i'll", "i've", "don't", "can't", "won't", "didn't", "isn't", "doesn't",
        "should", "would", "could", "have", "had", "what", "which", "when", "where", "why", "how",
        "this", "that", "these", "those", "there", "here", "from", "about", "into", "off", "up", "out",
        "just", "get", "got", "pick", "drop", "want", "need", "know", "think", "tomorrow", "tonight",
        "today", "yesterday", "please", "thanks", "sorry", "is", "are", "was", "were", "will", "been",
        "of", "for", "to", "at", "bring", "call", "meet", "send", "buy", "find", "keep", "leave",
        "let", "make", "take", "tell", "try", "wait", "help", "back", "around", "something", "anything",
        "someone", "because", "before", "after", "maybe", "already", "still", "soon", "late", "early",
        "borrow", "lend", "fix", "check", "sure", "busy", "running", "catch", "cancel", "reschedule",
    )
}

/**
 * One filled gap: the source range it replaces ([start] to [end], including any brackets),
 * the writer's own phrase, the fill options (the suggested one first) and a short note.
 */
data class Fill(
    val start: Int,
    val end: Int,
    val original: String,
    val options: List<String>,
    val note: String,
    val chosen: Int = 0,
) {
    init {
        require(start in 0..end)
        require(options.isNotEmpty() && chosen in options.indices)
    }

    val text: String get() = options[chosen]
    val alternatives: List<String> get() = options.filterIndexed { index, _ -> index != chosen }
}

/** A run of the result: the writer's own words, or a fill (by its index). */
data class Piece(val text: String, val fill: Int? = null)

/** The source message, the language the model wrote the fills in, and every fill in order. */
data class GapFill(val source: String, val language: String?, val fills: List<Fill>) {
    init {
        fills.zipWithNext().forEach { (a, b) -> require(a.end <= b.start) { "Fills overlap" } }
        require(fills.lastOrNull()?.let { it.end <= source.length } ?: true)
    }

    /** The source with the writer's words untouched and each gap replaced by its chosen fill. */
    val pieces: List<Piece>
        get() = buildList {
            var at = 0
            fills.forEachIndexed { index, fill ->
                if (fill.start > at) add(Piece(source.substring(at, fill.start)))
                add(Piece(fill.text, index))
                at = fill.end
            }
            if (at < source.length) add(Piece(source.substring(at)))
        }

    val text: String get() = pieces.joinToString("") { it.text }

    /** The same result with fill [index] using its option [option] (an index into its options). */
    fun choose(index: Int, option: Int): GapFill {
        val fill = fills.getOrNull(index) ?: return this
        if (option !in fill.options.indices) return this
        return copy(fills = fills.toMutableList().also { it[index] = fill.copy(chosen = option) })
    }

    companion object {
        /**
         * Places the model's fills on [source]. Each fill quotes the exact source text it
         * replaces, in order; a quote of a bracketed phrase without its brackets takes the
         * brackets too. Every marked gap must be covered. Throws [IllegalArgumentException]
         * when a quote can't be placed, overlaps another, or leaves a marked gap unfilled.
         */
        fun place(source: String, language: String?, quoted: List<QuotedFill>): GapFill {
            var cursor = 0
            val fills = quoted.map { answer ->
                val quote = answer.original.trim()
                require(quote.isNotEmpty()) { "Empty quote" }
                val found = source.indexOf(quote, cursor)
                require(found >= 0) { "Quote not found in order" }
                var start = found
                var end = found + quote.length
                if (start > 0 && source[start - 1] == '[' && end < source.length && source[end] == ']') {
                    start--
                    end++
                }
                cursor = end
                val options = (listOf(answer.filled) + answer.alternatives)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                require(options.isNotEmpty() && options.first() == answer.filled.trim()) { "Empty fill" }
                require(options.none { '[' in it || ']' in it }) { "Fill keeps brackets" }
                Fill(
                    start = start,
                    end = end,
                    original = quote.replace("[", "").replace("]", "").trim(),
                    options = options.take(1 + MAX_ALTERNATIVES),
                    note = answer.note.trim(),
                )
            }
            val unfilled = Gaps.marked(source).filter { gap -> fills.none { it.start <= gap.start && gap.end <= it.end } }
            require(unfilled.isEmpty()) { "Marked gap left unfilled" }
            return GapFill(source, language?.trim()?.takeIf { it.isNotEmpty() }, fills)
        }

        const val MAX_ALTERNATIVES = 3
    }
}

/** A fill as the model wrote it: the exact source text it replaces and what replaces it. */
data class QuotedFill(
    val original: String,
    val filled: String,
    val note: String = "",
    val alternatives: List<String> = emptyList(),
)
