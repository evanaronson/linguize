package com.evanaronson.linguize.core.vocab

/** What reading a deck file gave: its entries, or why there were none. */
sealed interface DeckParse {
    /** [entries] are ranked 1..n with no repeated lemma and part of speech; [skipped] rows had no usable word or repeated one. */
    data class Parsed(val entries: List<DeckEntry>, val skipped: Int) : DeckParse

    data class Failed(val reason: Reason) : DeckParse

    enum class Reason {
        /** Nothing but blank lines or comments. */
        Empty,

        /** Rows, but no column that holds words. */
        NoWords,
    }
}

/**
 * Lab, Vocabulary gaps: reads a frequency deck from CSV, semicolon-separated or tab-separated
 * text (Anki's "Notes in plain text" export included). The header is optional and flexible:
 * a word column (lemma, word, term, front, ...), optionally a rank or a frequency count, a part
 * of speech and a gloss. Without a header, columns are guessed from their contents.
 *
 * Rows keep the file's order unless it has a rank (ascending) or a frequency count
 * (descending). Ranks are then renumbered 1..n, and a repeated lemma (with the same part of speech) keeps its best rank.
 */
object DeckParser {
    /** A deck larger than this is cut; no frequency list a writer imports needs more. */
    const val MAX_ENTRIES = 20_000

    fun parse(text: String, language: String? = null): DeckParse {
        val all = text.removePrefix("\uFEFF").lines()
        // Anki names the columns that aren't fields: "#guid column:1", "#tags column:4".
        val ignored = all.mapNotNull { ANKI_COLUMN.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()?.minus(1) }.toSet()
        val lines = all.filterNot { it.isBlank() || isComment(it) }
        if (lines.isEmpty()) return DeckParse.Failed(DeckParse.Reason.Empty)
        val delimiter = delimiter(lines.take(SAMPLE))
        val records = records(lines.joinToString("\n"), delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (records.isEmpty()) return DeckParse.Failed(DeckParse.Reason.Empty)

        val header = readHeader(records.first())
        val body = if (header != null) records.drop(1) else records
        val columns = header ?: guessColumns(body.take(SAMPLE), ignored)
            ?: return DeckParse.Failed(DeckParse.Reason.NoWords)

        data class Row(
            val order: Int,
            val key: Double?,
            val lemma: String,
            val pos: String?,
            val gloss: String?,
            val forms: List<String>,
            val count: Long?,
        )
        var skipped = 0
        val rows = body.mapIndexedNotNull { index, cells ->
            val lemma = cells.getOrNull(columns.lemma)?.let { normalizeLemma(it, language) }
            if (lemma.isNullOrEmpty()) {
                skipped++
                return@mapIndexedNotNull null
            }
            val key = columns.order?.let { cells.getOrNull(it)?.trim()?.replace(",", "")?.toDoubleOrNull() }
            Row(
                order = index,
                key = key,
                lemma = lemma,
                pos = columns.pos?.let { PartOfSpeech.read(cells.getOrNull(it)) }?.let(VocabDeck::posToken),
                gloss = columns.gloss?.let { cells.getOrNull(it)?.let(::cleanText)?.takeIf(String::isNotEmpty) },
                forms = columns.forms?.let { column ->
                    cells.getOrNull(column).orEmpty().split(';', '|')
                        .map { cleanText(it).lowercase() }
                        .filter { form -> form.isNotEmpty() && form.all { it.isLetter() || it == '·' } }
                        .distinct()
                }.orEmpty(),
                count = columns.count?.let { cells.getOrNull(it)?.trim()?.replace(",", "")?.toDoubleOrNull()?.toLong() },
            )
        }
        val sorted = when (columns.orderKind) {
            OrderKind.Rank -> rows.sortedWith(compareBy<Row>({ it.key == null }, { it.key ?: 0.0 }, { it.order }))
            OrderKind.Count -> rows.sortedWith(compareBy<Row>({ it.key == null }, { -(it.key ?: 0.0) }, { it.order }))
            null -> rows
        }
        val seen = HashMap<String, Int>()
        val entries = ArrayList<DeckEntry>()
        for (row in sorted) {
            val key = if (row.pos == null) row.lemma else "${row.lemma}/${row.pos}"
            val earlier = seen[key]
            if (earlier != null) {
                // A repeated lexeme keeps its best rank; its forms still count toward it.
                val kept = entries[earlier]
                if (row.forms.isNotEmpty()) entries[earlier] = kept.copy(forms = (kept.forms + row.forms).distinct())
                skipped++
                continue
            }
            if (entries.size == MAX_ENTRIES) {
                skipped++
                continue
            }
            seen[key] = entries.size
            entries += DeckEntry(entries.size + 1, row.lemma, row.pos, row.gloss, row.forms, row.count)
        }
        return if (entries.isEmpty()) DeckParse.Failed(DeckParse.Reason.NoWords) else DeckParse.Parsed(entries, skipped)
    }

    /**
     * The form decks and the corpus are compared in: lowercase, without markup, articles,
     * a feminine ending after a comma ("bueno, buena") or a note in brackets.
     */
    fun normalizeLemma(raw: String, language: String? = null): String {
        var value = cleanText(raw).lowercase()
        value = value.replace(PARENTHETICAL, " ")
        value = value.split(',', '/', ';', '|').first()
        value = value.trim().trim { it in EDGE_PUNCTUATION }.trim()
        value = WHITESPACE.replace(value, " ")
        val words = value.split(' ')
        if (words.size > 1 && words.first() in articles(language)) value = words.drop(1).joinToString(" ")
        ELIDED_ARTICLE.find(value)?.let { if (language != "es") value = value.substring(it.range.last + 1) }
        return value.takeIf { word -> word.any { it.isLetter() } }.orEmpty()
    }

    private fun articles(language: String?) = when (language) {
        "es" -> setOf("el", "la", "los", "las", "un", "una")
        "ca" -> setOf("el", "la", "els", "les", "un", "una")
        else -> setOf("el", "la", "los", "las", "els", "les", "un", "una")
    }

    private fun cleanText(raw: String): String = raw
        .replace(HTML_BREAK, " ")
        .replace(HTML_TAG, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .let { WHITESPACE.replace(it, " ") }
        .trim()

    private enum class OrderKind { Rank, Count }

    private data class Columns(
        val lemma: Int,
        val order: Int? = null,
        val orderKind: OrderKind? = null,
        val pos: Int? = null,
        val gloss: Int? = null,
        val forms: Int? = null,
        val count: Int? = null,
    )

    private fun readHeader(cells: List<String>): Columns? {
        // A row with a number in it is data ("1,palabra"), even when a word looks like a column name.
        if (cells.any { it.trim().replace(",", "").toDoubleOrNull() != null }) return null
        val names = cells.map { cell -> cell.trim().lowercase().filter { it.isLetterOrDigit() || it == '#' } }
        val lemma = names.indexOfFirst { it in LEMMA_NAMES }.takeIf { it >= 0 } ?: return null
        val rank = names.indexOfFirst { it == "#" || it in RANK_NAMES || "rank" in it }.takeIf { it >= 0 }
        val count = names.indexOfFirst { it in COUNT_NAMES }.takeIf { it >= 0 }
        val pos = names.indexOfFirst { it in POS_NAMES }.takeIf { it >= 0 }
        val forms = names.indexOfFirst { it in FORMS_NAMES }.takeIf { it >= 0 && it != lemma }
        val gloss = names.indexOfFirst { it in GLOSS_NAMES }.takeIf { it >= 0 && it != lemma }
            ?: names.indices.firstOrNull { it != lemma && it != rank && it != count && it != pos && names[it] in BACK_NAMES }
        return Columns(
            lemma = lemma,
            forms = forms,
            count = count,
            order = rank ?: count,
            orderKind = when {
                rank != null -> OrderKind.Rank
                count != null -> OrderKind.Count
                else -> null
            },
            pos = pos,
            gloss = gloss,
        )
    }

    /** No header: a numeric column orders the rows, the first text column holds the words. */
    private fun guessColumns(sample: List<List<String>>, ignored: Set<Int>): Columns? {
        if (sample.isEmpty()) return null
        val usable = (0 until sample.maxOf { it.size }).filter { it !in ignored }
        fun share(column: Int, test: (String) -> Boolean): Double =
            sample.count { row -> row.getOrNull(column)?.trim()?.let(test) == true }.toDouble() / sample.size
        val numeric = usable.filter { column -> share(column) { it.replace(",", "").toDoubleOrNull() != null } >= 0.9 }
        val pos = usable.firstOrNull { it !in numeric && share(it) { cell -> PartOfSpeech.read(cell) != null } >= 0.8 }
        val text = usable.filter { it !in numeric && it != pos && share(it) { cell -> cell.any(Char::isLetter) } >= 0.5 }
        val lemma = text.firstOrNull() ?: return null
        val order = numeric.firstOrNull()
        val kind = order?.let { column ->
            val values = sample.mapNotNull { it.getOrNull(column)?.trim()?.replace(",", "")?.toDoubleOrNull() }
            val rising = values.zipWithNext().count { (a, b) -> b >= a }
            if (rising * 2 >= values.size - 1) OrderKind.Rank else OrderKind.Count
        }
        return Columns(
            lemma = lemma, order = order, orderKind = kind, pos = pos, gloss = text.getOrNull(1),
            count = order.takeIf { kind == OrderKind.Count },
        )
    }

    /**
     * Tab when there is one; otherwise whichever of comma and semicolon the first line (usually
     * the header) uses more. A field may itself list forms with semicolons, so later lines
     * only decide when the first line has neither.
     */
    private fun delimiter(lines: List<String>): Char {
        if (lines.any { '\t' in it }) return '\t'
        fun pick(sample: List<String>): Char? {
            val semicolons = sample.sumOf { line -> line.count { it == ';' } }
            val commas = sample.sumOf { line -> line.count { it == ',' } }
            return when {
                semicolons > commas -> ';'
                commas > 0 -> ','
                else -> null
            }
        }
        return pick(lines.take(1)) ?: pick(lines) ?: ','
    }

    /** RFC 4180 records: quoted fields may hold the delimiter, doubled quotes and line breaks. */
    private fun records(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' && (quoted || field.isBlank()) -> { quoted = !quoted; if (quoted) field.clear() }
                quoted -> field.append(char)
                char == delimiter -> { row += field.toString(); field.clear() }
                char == '\n' -> { row += field.toString(); field.clear(); records += row; row = ArrayList() }
                char == '\r' -> Unit
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            records += row
        }
        return records
    }

    /** Anki's "#separator:tab" style directives and "# notes" lines. */
    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("# ") || ANKI_DIRECTIVE.matches(trimmed) || trimmed.startsWith("//")
    }

    private const val SAMPLE = 30
    private val ANKI_COLUMN = Regex("""#(?:guid|notetype|deck|tags) column:(\d+)""", RegexOption.IGNORE_CASE)
    private val ANKI_DIRECTIVE = Regex("""#[A-Za-z][A-Za-z ]*:.*""")
    private val HTML_BREAK = Regex("""<br\s*/?>|</div>|</p>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val WHITESPACE = Regex("""\s+""")
    private val PARENTHETICAL = Regex("""\([^)]*\)|\[[^]]*]""")
    private val ELIDED_ARTICLE = Regex("""^l['’]\s*(?=\p{L})""")
    private const val EDGE_PUNCTUATION = "¡!¿?.:\"'“”«»…-–—*"

    private val LEMMA_NAMES = setOf(
        "lemma", "lema", "word", "words", "term", "headword", "palabra", "paraula", "mot", "front", "expression",
        "vocab", "vocabulary", "spanish", "catalan", "español", "espanol", "català", "catala", "castellano",
        "target", "entry", "text", "form", "token", "lemmaform",
    )
    private val RANK_NAMES = setOf("rank", "ranking", "no", "nº", "n", "num", "position", "rango", "posición", "order", "id", "index")
    private val COUNT_NAMES = setOf(
        "frequency", "freq", "count", "occurrences", "abs", "absolutefrequency", "frecuencia", "freqüència",
        "frequencia", "freqcount", "tokens", "hits", "permillion", "fpm", "ipm",
    )
    private val POS_NAMES = setOf("pos", "partofspeech", "part", "type", "category", "tag", "categoria", "categoría", "wordclass", "class")
    private val GLOSS_NAMES = setOf(
        "gloss", "glossen", "english", "meaning", "translation", "definition", "en", "traducción", "traducció",
        "significado", "significat", "eng", "gloss_en",
    )
    private val BACK_NAMES = setOf("back", "answer", "reverse")
    private val FORMS_NAMES = setOf("forms", "wordforms", "inflections", "variants", "formas", "formes")
}
