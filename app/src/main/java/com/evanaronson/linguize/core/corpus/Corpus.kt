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
 * Lab: maps a written word to its dictionary forms (lemmas) in a language, e.g. "fuimos" to
 * {"ir", "ser"}. Words are lowercase. [Identity] knows no morphology; a richer one replaces it.
 */
fun interface Lemmatizer {
    fun lemmas(word: String, language: String): Set<String>

    companion object {
        val Identity = Lemmatizer { word, _ -> setOf(word) }
    }
}
