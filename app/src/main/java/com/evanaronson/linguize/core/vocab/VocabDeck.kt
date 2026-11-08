package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import kotlinx.serialization.Serializable

/**
 * Lab, Vocabulary gaps: one row of a frequency deck, a lexeme when [pos] is known ("ser"/v)
 * and a bare lemma otherwise. [rank] starts at 1 (most frequent); [lemma] is lowercase.
 * [pos] is a shared [Pos] token, or null when unknown. [forms] are written forms the deck
 * itself counts toward this row ("fue" for "ser"); [count] is its corpus count, if any.
 */
@Serializable
data class DeckEntry(
    val rank: Int,
    val lemma: String,
    val pos: String? = null,
    val gloss: String? = null,
    val forms: List<String> = emptyList(),
    val count: Long? = null,
) {
    /** Entries such as "a menudo" are matched as phrases rather than as single words. */
    val isPhrase: Boolean get() = lemma.any { it == ' ' }

    /** Unique within a deck: "ser/v" for a lexeme, "ser" for a lemma. */
    val key: String get() = if (pos == null) lemma else "$lemma/$pos"
}

/** Whether a deck counts lexemes (lemma with part of speech) or lemmas. */
enum class VocabLevel { Lexeme, Lemma }

/**
 * A ranked list of lexemes or lemmas in one language. Bundled decks ship as assets; imported
 * decks are the writer's own CSV or Anki export, kept in Lab-local storage.
 */
data class VocabDeck(
    val id: String,
    val name: String,
    /** ISO 639-1 code, e.g. "es" or "ca". */
    val language: String,
    val entries: List<DeckEntry>,
    val bundled: Boolean,
    /**
     * The entries are written forms, not lemmas: no morphology grouped them yet, so "vaig" and
     * "anar" are separate entries and a word counts only toward its exact form.
     */
    val formsOnly: Boolean = false,
    val level: VocabLevel = VocabLevel.Lexeme,
) {
    val size: Int get() = entries.size

    /** Whether any row has a part of speech, so the lexeme and lemma views differ. */
    val hasLexemes: Boolean get() = entries.any { it.pos != null }

    /**
     * The same deck by lemma: lexemes that share a lemma ("como"/conj, "como"/adv) become one
     * row, ranked where its first lexeme was, with their forms and counts combined.
     */
    fun byLemma(): VocabDeck {
        if (level == VocabLevel.Lemma) return this
        val groups = LinkedHashMap<String, MutableList<DeckEntry>>()
        entries.forEach { groups.getOrPut(it.lemma) { ArrayList() } += it }
        val merged = groups.values.mapIndexed { index, rows ->
            DeckEntry(
                rank = index + 1,
                lemma = rows.first().lemma,
                pos = null,
                gloss = rows.mapNotNull { it.gloss }.distinct().joinToString("; ").ifEmpty { null },
                forms = rows.flatMap { it.forms }.distinct(),
                count = rows.mapNotNull { it.count }.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
        return copy(entries = merged, level = VocabLevel.Lemma)
    }

    companion object {
        /**
         * Groups a list of written forms into lexemes with [lexemes] (a lemmatizer): each
         * lexeme's count is the sum of its forms' counts, and lexemes are ranked by that sum.
         * An ambiguous form counts toward every candidate. With no morphology (only
         * [Pos.Unknown] answers that repeat the form) every form stays its own entry, and the
         * deck says so ([formsOnly]).
         */
        fun fromForms(
            id: String,
            name: String,
            language: String,
            forms: List<DeckEntry>,
            bundled: Boolean,
            lexemes: (String) -> Set<Lexeme>,
        ): VocabDeck {
            class Group(val lemma: String, val pos: String?, val firstRank: Int) {
                var count = 0L
                val forms = LinkedHashSet<String>()
            }
            val groups = LinkedHashMap<String, Group>()
            for (form in forms) {
                val targets = lexemes(form.lemma).filter { it.lemma.isNotBlank() }
                    .ifEmpty { setOf(Lexeme(form.lemma, Pos.Unknown)) }
                for (lexeme in targets) {
                    val pos = posToken(lexeme.pos)
                    val lemma = lexeme.lemma.lowercase()
                    val group = groups.getOrPut(if (pos == null) lemma else "$lemma/$pos") { Group(lemma, pos, form.rank) }
                    group.count += form.count ?: 0L
                    group.forms += form.lemma
                }
            }
            val ranked = groups.values.sortedWith(compareByDescending<Group> { it.count }.thenBy { it.firstRank })
            val entries = ranked.mapIndexed { index, group ->
                DeckEntry(index + 1, group.lemma, group.pos, forms = group.forms.toList(), count = group.count)
            }
            val grouped = groups.values.any { it.pos != null || it.forms.size > 1 || it.forms.single() != it.lemma }
            return VocabDeck(id, name, language, entries, bundled, formsOnly = !grouped)
        }

        /** The token a deck row stores: null when nothing is known about the part of speech. */
        fun posToken(pos: Pos): String? = pos.takeIf { it != Pos.Unknown }?.token
    }
}

/** Reading parts of speech from deck files, on top of the shared [Pos] tokens. */
object PartOfSpeech {
    /** The many ways decks write a part of speech; null when it isn't one we know. */
    fun read(raw: String?): Pos? {
        val value = raw?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        Pos.of(value).takeIf { it != Pos.Unknown }?.let { return it }
        return when (value) {
            "nm", "nf", "sustantivo", "nombre", "substantiu", "nom", "s" -> Pos.Noun
            "vb", "verbo" -> Pos.Verb
            "adjetivo", "adjectiu", "aj" -> Pos.Adjective
            "adverbio", "r", "av" -> Pos.Adverb
            "pronombre", "pronom", "pn" -> Pos.Pronoun
            "determinante", "determinant", "d" -> Pos.Determiner
            "artículo" -> Pos.Article
            "preposición", "preposició", "p" -> Pos.Preposition
            "conjunción", "conjunció", "c", "cj" -> Pos.Conjunction
            "número" -> Pos.Numeral
            "interjección", "interjecció", "intj", "ij" -> Pos.Interjection
            else -> null
        }
    }
}
