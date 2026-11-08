package com.evanaronson.linguize.core.corpus

/**
 * Lab: one piece of text the writer typed themselves, exactly as typed, before any correction.
 *
 * Only the writer's own input is evidence of what they know. Model output (corrections,
 * rewordings, replies, translations) never enters the corpus. Within [text], [corrected] marks
 * the character ranges the writer later accepted a fix for: they tried those words, but got
 * them wrong, so analysers must not count them as known.
 */
data class Utterance(
    /** Unique across sources, stable across rebuilds: "<source>:<source's own id>". */
    val id: String,
    /** The feature it came from, e.g. "check" or "chat". */
    val source: String,
    /** ISO 639-1 code of the language it was written in ("es", "ca"), or null when unknown. */
    val language: String?,
    /** Exactly what the writer typed. */
    val text: String,
    /** Character spans of [text] that an accepted fix replaced. */
    val corrected: List<Span> = emptyList(),
    /** When it was written, epoch milliseconds. */
    val at: Long,
) {
    init {
        require(corrected.all { it.end <= text.length }) { "Corrected spans must lie within the text" }
    }

    /** Whether the characters `[start, end)` overlap a corrected span. */
    fun isCorrected(start: Int, end: Int): Boolean = corrected.any { it.overlaps(start, end) }
}

/** Characters `[start, end)` of a text: [start] included, [end] excluded. */
data class Span(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "A span starts at or before its end" }
    }

    /** Whether this span and `[from, to)` share a character. An empty span overlaps nothing. */
    fun overlaps(from: Int, to: Int): Boolean = start < to && from < end
}

/** Lab: a feature that holds writer input. Each source reads its own storage; nothing is copied. */
interface CorpusSource {
    /** Matches [Utterance.source]. */
    val name: String

    /** Everything this source currently holds. Deleted input must no longer appear. */
    suspend fun utterances(): List<Utterance>
}

/**
 * Lab: all writer input across features, oldest first. It is derived on demand from the
 * sources, so deleting from a source removes the input from everything built on the corpus.
 * A source that fails is skipped rather than failing the whole corpus.
 */
class Corpus(private val sources: List<CorpusSource>) {
    suspend fun utterances(language: String? = null): List<Utterance> =
        sources.flatMap { source -> runCatching { source.utterances() }.getOrElse { emptyList() } }
            .filter { language == null || it.language == language }
            .distinctBy { it.id }
            .sortedBy { it.at }

    /** The languages present, most used first. */
    suspend fun languages(): List<String> =
        utterances().mapNotNull { it.language }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
}

/**
 * Lab: a lexeme, the unit a vocabulary is made of: a dictionary form plus its part of speech.
 * "llama" the noun and "llamar" the verb are different lexemes; "la" the article and "la" the
 * pronoun are different lexemes with the same [lemma]. Lemmas are lowercase.
 */
data class Lexeme(val lemma: String, val pos: Pos)

/** Lab: parts of speech shared by every Lab feature (decks, morphology, word counts). */
enum class Pos(val token: String) {
    Noun("n"), Verb("v"), Adjective("adj"), Adverb("adv"), Pronoun("pron"), Determiner("det"),
    Article("art"), Preposition("prep"), Conjunction("conj"), Numeral("num"), Interjection("interj"),
    /** Known word, part of speech not classified. */
    Other("other"),
    /** Nothing is known about the word. */
    Unknown("unknown");

    companion object {
        /** Reads a token, also accepting common spellings ("noun", "verb", "determiner"); else [Unknown]. */
        fun of(token: String?): Pos = when (val t = token?.trim()?.lowercase()) {
            null, "" -> Unknown
            "noun" -> Noun
            "verb" -> Verb
            "adjective" -> Adjective
            "adverb" -> Adverb
            "pronoun" -> Pronoun
            "determiner" -> Determiner
            "article" -> Article
            "preposition" -> Preposition
            "conjunction" -> Conjunction
            "numeral", "number" -> Numeral
            "interjection" -> Interjection
            "none" -> Other
            else -> entries.firstOrNull { it.token == t } ?: Unknown
        }
    }
}

/**
 * Lab: analyses a written word into the lexemes it can be in a language, e.g. "fuimos" to
 * {ir/v, ser/v} and "la" to {el/art, ella/pron}. Words are lowercase. An ambiguous word returns
 * every candidate; features decide how to count it (and say so). [Identity] knows no
 * morphology: the word is its own lemma, part of speech [Pos.Unknown].
 */
fun interface Lemmatizer {
    fun lexemes(word: String, language: String): Set<Lexeme>

    /** The dictionary forms, ignoring part of speech. */
    fun lemmas(word: String, language: String): Set<String> = lexemes(word, language).mapTo(LinkedHashSet()) { it.lemma }

    companion object {
        val Identity = Lemmatizer { word, _ -> setOf(Lexeme(word, Pos.Unknown)) }
    }
}
