package com.evanaronson.linguize.core.corpus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

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
    /**
     * Where, within its source, it was written: for "check", History's origin token ("menu",
     * "button", "chat"), so analyses can compare writing to people with chat practice. Null when unknown.
     */
    val origin: String? = null,
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

    /** The requested successful input. Deleted input must no longer appear; an unreadable source throws. */
    suspend fun utterances(): List<Utterance>

    /** A source may supply bounded coverage or an incomplete read instead of claiming all input. */
    suspend fun read(): CorpusRead = CorpusRead(utterances(), listOf(CorpusSourceCoverage(name, CorpusReadStatus.Complete)))
}

/** Complete means the requested window was read; [CorpusSourceCoverage.hasMore] describes older input. */
enum class CorpusReadStatus { Complete, Partial, Failed }

/** Pure source metadata: a bounded successful read is distinct from a partial or failed read. */
data class CorpusSourceCoverage(
    val source: String,
    val status: CorpusReadStatus,
    val limit: Int? = null,
    val hasMore: Boolean? = null,
    /** Process-local identity, when the source supports it; never a persisted fingerprint. */
    val revision: Long? = null,
    /** Source records selected and successfully loaded, before eligibility filtering. */
    val selectedItems: Int? = null,
    val loadedItems: Int? = null,
)

/** Successful portions remain inspectable, but complete-only consumers must explicitly require them. */
data class CorpusRead(val utterances: List<Utterance>, val sources: List<CorpusSourceCoverage>) {
    val status: CorpusReadStatus
        get() = when {
            sources.all { it.status == CorpusReadStatus.Complete } -> CorpusReadStatus.Complete
            sources.all { it.status == CorpusReadStatus.Failed } -> CorpusReadStatus.Failed
            else -> CorpusReadStatus.Partial
        }

    fun requireComplete(): List<Utterance> {
        if (status != CorpusReadStatus.Complete) throw IOException("Writer evidence could not be read completely")
        return utterances
    }
}

/**
 * Lab: all writer input across features, oldest first. It is derived on demand from the
 * sources, so deleting from a source removes the input from everything built on the corpus.
 * Source failures remain explicit, and cancellation stops the whole read immediately.
 */
class Corpus(private val sources: List<CorpusSource>) {
    suspend fun read(language: String? = null): CorpusRead {
        val reads = sources.map { source ->
            currentCoroutineContext().ensureActive()
            val result = try {
                source.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CorpusRead(emptyList(), listOf(CorpusSourceCoverage(source.name, CorpusReadStatus.Failed)))
            }
            currentCoroutineContext().ensureActive()
            result
        }
        val utterances = reads.flatMap { it.utterances }.filter { language == null || it.language == language }
            .distinctBy { it.id }
            .sortedBy { it.at }
        return CorpusRead(utterances, reads.flatMap { it.sources })
    }

    /** Strict convenience for analysers that cannot truthfully consume partial evidence. */
    suspend fun utterances(language: String? = null): List<Utterance> = read(language).requireComplete()

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
