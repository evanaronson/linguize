package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.vocab.usedDeckKeys
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabSuggestions
import java.security.MessageDigest

/** A kind of writing the writer seldom does. */
enum class TargetRegister(val prompt: String) {
    Formal("formal writing (usted or vostè, polite requests, a message to an office or a landlord)"),
    Casual("casual texting with friends"),
}

/** A word to bring up: its dictionary form and, when the deck has one, a gloss. */
data class TargetWord(val lemma: String, val gloss: String?)

/**
 * Lab Chat: what the partner can create openings for, in one language: verb forms the writer
 * rarely or never writes, top words they haven't used, and kinds of writing they seldom do.
 * Built from the corpus, weighted toward real (non-chat) writing once there is enough of it.
 */
data class PracticeTargets(
    val language: String,
    val cells: List<Cell> = emptyList(),
    val words: List<TargetWord> = emptyList(),
    val registers: List<TargetRegister> = emptyList(),
    /** Whether the gaps were read from real writing only (enough of it), not chat practice too. */
    val fromRealWriting: Boolean = false,
) {
    val isEmpty get() = cells.isEmpty() && words.isEmpty() && registers.isEmpty()

    /** The block the partner's instructions end with; "" when there is nothing to steer toward. Bounded. */
    fun render(): String {
        if (isEmpty) return ""
        return buildList {
            if (cells.isNotEmpty()) {
                add("- Verb forms they rarely or never write: " + cells.joinToString("; ") { describe(it) } + ".")
            }
            if (words.isNotEmpty()) {
                add("- Common words they haven't used yet: " + words.joinToString(", ") { word ->
                    word.gloss?.takeIf { it.isNotBlank() }?.let { "${word.lemma} (${it.take(40)})" } ?: word.lemma
                } + ".")
            }
            if (registers.isNotEmpty()) {
                add("- Kinds of writing they seldom do: " + registers.joinToString("; ") { it.prompt } + ".")
            }
        }.joinToString("\n").take(PracticeTargetsBuilder.MAX_BLOCK)
    }

    private fun describe(cell: Cell): String {
        val tense = cell.tense.token.replace('_', ' ')
        return cell.person?.let { "$tense, ${personName(it)}" } ?: tense
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
    /** With at least this much real (non-chat) writing, gaps are read from it alone. */
    const val MIN_REAL = 30

    /** Fewer utterances than this say too little about verb forms or registers. */
    const val MIN_EVIDENCE = 8

    const val MAX_CELLS = 3
    const val MAX_WORDS = 5
    const val MAX_BLOCK = 900

    /** A cell used less than this (uses, shared between ambiguous readings) counts as rare. */
    private const val RARE = 1.0

    /** The simple tenses worth steering toward, in the order the grid lists them, and the persons people text in. */
    private val PERSONS = listOf(Person.S1, Person.S3, Person.P1, Person.S2)

    fun build(
        utterances: List<Utterance>,
        language: String,
        morphology: Morphology?,
        deck: VocabDeck?,
        lemmatizer: Lemmatizer,
    ): PracticeTargets {
        val inLanguage = utterances.filter { it.language == language }
        val real = inLanguage.filter { it.origin != CHAT_ORIGIN }
        val fromReal = real.size >= MIN_REAL
        val base = if (fromReal) real else inLanguage
        val cells = if (morphology != null && base.size >= MIN_EVIDENCE && morphology.supports(language)) {
            rareCells(ConjugationMap.build(base, language, morphology))
        } else emptyList()
        val words = deck?.takeIf { it.language == language }?.let { d ->
            VocabSuggestions.targets(d, usedDeckKeys(d, base, lemmatizer), MAX_WORDS).map { TargetWord(it.lemma, it.gloss) }
        }.orEmpty()
        return PracticeTargets(language, cells, words, registerGaps(base, language), fromReal)
    }

    /**
     * Up to [MAX_CELLS] tenses (simple ones, in grid order after the present) whose least-used
     * everyday person is rare, one cell per tense so the set is varied.
     */
    fun rareCells(report: ConjugationReport): List<Cell> =
        report.rows.filter { !it.compound && it != com.evanaronson.linguize.core.morphology.Tense.Present }
            .mapNotNull { tense ->
                PERSONS.filter { ConjugationMap.applies(tense, it) }
                    .map { Cell(tense, it) }
                    .map { it to (report.stats(it)?.weight ?: 0.0) }
                    .filter { it.second < RARE }
                    .minByOrNull { it.second }?.first
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
    private const val CHAT_ORIGIN = "chat"

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
