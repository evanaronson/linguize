package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.ClosedClass
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.usedDeckKeys
import com.evanaronson.linguize.core.vocab.VocabDeck
import java.security.MessageDigest

/** A kind of writing the writer seldom does. */
enum class TargetRegister {
    Formal,
    Casual;

    /** How the block names it, with the forms of address of [language] only (never another language's). */
    fun prompt(language: String): String = when (this) {
        Formal -> "formal writing (" + when (language) {
            "es" -> "usted"
            "ca" -> "vostè"
            else -> "the polite form of address"
        } + ", polite requests, a message to an office or a landlord)"
        Casual -> "casual texting with friends"
    }
}

/** A word to bring up: its dictionary form and, when the deck has one, a gloss. */
data class TargetWord(val lemma: String, val gloss: String?)

/**
 * Lab Chat: what the partner can create openings for, in one language: verb forms the writer
 * rarely or never writes, top words they haven't used, and kinds of writing they seldom do.
 * Built from everything the writer typed in that language, chat included, as Words and
 * Conjugations count it: a use inside a corrected span doesn't count as known.
 */
data class PracticeTargets(
    val language: String,
    val cells: List<Cell> = emptyList(),
    val words: List<TargetWord> = emptyList(),
    val registers: List<TargetRegister> = emptyList(),
) {
    val isEmpty get() = cells.isEmpty() && words.isEmpty() && registers.isEmpty()

    /** The block the partner's instructions end with; "" when there is nothing to steer toward. Bounded. */
    fun render(): String {
        if (isEmpty) return ""
        return buildList {
            if (cells.isNotEmpty()) {
                // Grouped by tense, most useful first: "future (I, we); conditional (I)".
                add("- Verb forms they rarely or never write: " + cells.groupBy { it.tense }.entries.joinToString("; ") { (tense, group) ->
                    val persons = group.mapNotNull { it.person }
                    if (persons.isEmpty()) tenseName(tense) else "${tenseName(tense)} (${persons.joinToString(", ") { personName(it) }})"
                } + ".")
            }
            if (words.isNotEmpty()) {
                add("- Common words they haven't used yet: " + words.joinToString(", ") { word ->
                    word.gloss?.takeIf { it.isNotBlank() }?.let { "${word.lemma} (${it.take(MAX_GLOSS)})" } ?: word.lemma
                } + ".")
            }
            if (registers.isNotEmpty()) {
                add("- Kinds of writing they seldom do: " + registers.joinToString("; ") { it.prompt(language) } + ".")
            }
        }.joinToString("\n").take(PracticeTargetsBuilder.MAX_BLOCK)
    }

    private fun tenseName(tense: Tense) = when (tense) {
        Tense.PeriphrasticPast -> "past with vaig + infinitive"
        Tense.Perfect -> "present perfect"
        else -> tense.token.replace('_', ' ')
    }

    private companion object {
        const val MAX_GLOSS = 30
    }

    private fun personName(person: Person) = when (person) {
        Person.S1 -> "I"
        Person.S2 -> "you (one friend)"
        Person.S3 -> "he or she"
        Person.P1 -> "we"
        Person.P2 -> "you (several)"
        Person.P3 -> "they"
    }
}

/** Builds [PracticeTargets]. Pure: the caller reads the corpus and the deck, off the main thread. */
object PracticeTargetsBuilder {
    /** Fewer utterances than this say too little about verb forms or registers. */
    const val MIN_EVIDENCE = 8

    /** A pool the partner chooses from, not a fixed list: every rare everyday cell, ~30 words. */
    const val MAX_CELLS = 40
    const val MAX_WORDS = 30
    const val MAX_BLOCK = 2_500

    /** A cell used less than this (uses, shared between ambiguous readings) counts as rare. */
    private const val RARE = 1.0

    /** The persons people text in, most useful first. */
    private val PERSONS = listOf(Person.S1, Person.S3, Person.P1, Person.S2, Person.P3)

    /** Commands people text: to a friend, and "let's". */
    private val IMPERATIVE_PERSONS = listOf(Person.S2, Person.P1)

    /**
     * Everyday tenses worth steering toward, most useful first. Catalan speaks of the past with
     * "vaig anar", not the literary simple preterite; both languages use the present perfect.
     */
    private fun everydayTenses(language: String): List<Tense> = when (language) {
        "ca" -> listOf(
            Tense.PeriphrasticPast, Tense.Imperfect, Tense.Future, Tense.Conditional,
            Tense.PresentSubjunctive, Tense.ImperfectSubjunctive, Tense.Imperative, Tense.Perfect,
        )
        "es" -> listOf(
            Tense.Preterite, Tense.Imperfect, Tense.Future, Tense.Conditional,
            Tense.PresentSubjunctive, Tense.ImperfectSubjunctive, Tense.Imperative, Tense.Perfect,
        )
        else -> ConjugationMap.rows(language).filter { !it.compound && it != Tense.Present }
    }

    fun build(
        utterances: List<Utterance>,
        language: String,
        morphology: Morphology?,
        deck: VocabDeck?,
        lemmatizer: Lemmatizer,
    ): PracticeTargets {
        // Everything the writer typed in this language counts, chat included; corrected spans don't.
        val written = utterances.filter { it.language == language }
        val cells = if (morphology != null && written.size >= MIN_EVIDENCE && morphology.supports(language)) {
            rareCells(ConjugationMap.build(written, language, morphology))
        } else emptyList()
        val words = deck?.takeIf { it.language == language }?.let { d ->
            conversationWords(d, usedDeckKeys(d, written, lemmatizer), MAX_WORDS).map { TargetWord(it.lemma, it.gloss) }
        }.orEmpty()
        return PracticeTargets(language, cells, words, registerGaps(written, language))
    }

    /**
     * Up to [count] unused words a conversation can lead to, in deck (frequency) order, each
     * lemma once: nouns, verbs and adjectives. Function words (the closed-class table, and the
     * few that decks tag as content: "mismo", "mateix", auxiliaries, words that live in set
     * phrases), numbers, proper names (untagged in the decks) and very short words are
     * skipped. Adverbs that aren't function words only fill in when too few of those are left.
     */
    fun conversationWords(deck: VocabDeck, used: Set<String>, count: Int = MAX_WORDS): List<DeckEntry> {
        val language = deck.language
        val candidates = deck.entries.asSequence()
            .filter { it.key !in used && !it.isPhrase && steerable(it.lemma, language) }
        // A deck without parts of speech (an imported word list) can only be filtered by spelling.
        val content = candidates.filter { !deck.hasLexemes || Pos.of(it.pos) in CONTENT }.distinctBy { it.lemma }.take(count).toList()
        if (content.size >= count) return content
        val lemmas = content.mapTo(HashSet()) { it.lemma }
        val adverbs = candidates.filter { Pos.of(it.pos) == Pos.Adverb && it.lemma !in lemmas }.distinctBy { it.lemma }
        return (content + adverbs.take(count - content.size)).sortedBy { it.rank }
    }

    private fun steerable(lemma: String, language: String): Boolean =
        lemma.codePointCount(0, lemma.length) >= MIN_WORD &&
            lemma.all { it.isLetter() || it == '-' || it == '\'' || it == '·' } &&
            ClosedClass.lexemes(lemma, language) == null &&
            lemma !in UNSTEERABLE[language].orEmpty()

    private val CONTENT = setOf(Pos.Noun, Pos.Verb, Pos.Adjective)

    /** Shorter words are mostly function words or fragments, and too slight to steer toward. */
    private const val MIN_WORD = 3

    /**
     * Words the decks tag as nouns, verbs or adjectives that a conversation can't lead to:
     * determiner-like adjectives, auxiliaries, words that live in set phrases or greetings,
     * and vulgarities.
     */
    private val UNSTEERABLE = mapOf(
        "es" to setOf(
            "mismo", "propio", "cierto", "tal", "otro", "cualquiera", "haber", "ser", "estar", "poder", "deber",
            "gracias", "favor", "adiós", "acuerdo", "supuesto", "hecho", "vez", "buena", "buenas", "buenos",
            "señor", "señora", "tío", "tía", "mierda", "cosa", "verdad", "claro", "vale",
        ),
        "ca" to setOf(
            "mateix", "propi", "cert", "tal", "altre", "qualsevol", "haver", "ser", "ésser", "estar", "poder", "deure",
            "gràcies", "favor", "adéu", "acord", "fet", "vegada", "bona", "bones", "bon",
            "senyor", "senyora", "merda", "cosa", "veritat", "clar", "vale",
            // Lemmatizer artefacts: "plau" is "si us plau", "seu" mostly the possessive.
            "plaure", "seure",
        ),
    )

    /**
     * Every rare everyday cell, up to [MAX_CELLS]: the everyday tenses most useful first, and
     * within each the persons people text in (I, he or she, we, you, they; commands: you and "let's").
     */
    fun rareCells(report: ConjugationReport): List<Cell> =
        everydayTenses(report.language).filter { it in report.rows }
            .flatMap { tense ->
                (if (tense == Tense.Imperative) IMPERATIVE_PERSONS else PERSONS).filter { ConjugationMap.applies(tense, it) }
                    .map { Cell(tense, it) }
                    .filter { (report.stats(it)?.weight ?: 0.0) < RARE }
            }
            .take(MAX_CELLS)

    /**
     * Formal writing when almost none of the writer's text carries a formal marker, casual
     * when almost none carries a casual one. Says nothing with too little text.
     */
    fun registerGaps(utterances: List<Utterance>, language: String): List<TargetRegister> {
        if (utterances.size < MIN_EVIDENCE) return emptyList()
        val formal = FORMAL[language] ?: return emptyList()
        val casual = CASUAL[language] ?: return emptyList()
        fun share(markers: List<Regex>) = utterances.count { u -> markers.any { it.containsMatchIn(u.text) } }.toDouble() / utterances.size
        return buildList {
            if (share(formal) < SELDOM) add(TargetRegister.Formal)
            if (share(casual) < SELDOM) add(TargetRegister.Casual)
        }
    }

    /** A short, stable hash of the evidence for [language], so a cached block is rebuilt when it changes. */
    fun fingerprint(utterances: List<Utterance>, language: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        utterances.filter { it.language == language }.sortedBy { it.id }.forEach { u ->
            digest.update("${u.id}\u0000${u.text.hashCode()}\u0000${u.corrected}\u0000${u.origin}\n".toByteArray())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private const val SELDOM = 0.05

    private fun words(vararg words: String) = words.map { Regex("(?i)(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])") }

    private val FORMAL = mapOf(
        "es" to words("usted", "ustedes", "atentamente", "estimado", "estimada", "cordialmente", "disculpe", "le saluda", "un saludo cordial"),
        "ca" to words("vostè", "vostès", "atentament", "benvolgut", "benvolguda", "cordialment", "disculpi", "salutacions cordials"),
    )
    private val CASUAL = mapOf(
        "es" to words("jaja", "jajaja", "jeje", "tío", "tía", "guay", "vale", "porfa", "besos", "un beso", "qué tal"),
        "ca" to words("jaja", "jajaja", "ei", "petons", "guai", "vale", "porfa", "què tal", "una abraçada"),
    )
}
