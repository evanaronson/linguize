package com.evanaronson.linguize.core.morphology

import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * A guessed verb is kept only with evidence that the verb exists; otherwise a guessed noun
 * wins. "maneres" ends like "perderes" (simple past of a -re verb) but is the plural of
 * "manera": there is no verb "manre".
 */
class GuessedVerbEvidenceTest {
    /** A small list standing in for a bundled frequency deck. */
    private val evidence = mapOf(
        "ca" to VerbEvidence(
            verbs = emptyList(),
            forms = listOf("manera", "maneres", "cadira", "frontera", "màscara", "teoria", "programar", "enxampàvem", "taula"),
        ),
        "es" to VerbEvidence(
            verbs = listOf("bloquear"),
            forms = listOf("cámara", "cámaras", "lámpara", "préstamo", "bloquear", "editando", "mesa"),
        ),
    )
    private val withList = Morphology { evidence[it] }
    private val withoutList = SharedMorphology.instance

    private fun guessedVerbs(morphology: Morphology, word: String, language: String) =
        morphology.verbs(word, language).filter { it.guessed }.map { it.lemma }.toSet()

    private fun nouns(morphology: Morphology, word: String, language: String) =
        morphology.analyse(word, language).filter { it.pos == PartOfSpeech.Noun }.map { it.lemma }.toSet()

    @Test
    fun manereIsTheNounManeraNotAVerb() {
        for (morphology in listOf(withList, withoutList)) {
            assertEquals(emptySet<String>(), guessedVerbs(morphology, "maneres", "ca"))
            assertEquals(setOf("manera"), nouns(morphology, "maneres", "ca"))
            assertEquals(setOf(Lexeme("manera", Pos.Noun)), MorphologyLemmatizer(morphology).lexemes("maneres", "ca"))
        }
    }

    @Test
    fun catalanFemininePluralsAreNouns() {
        // -eres, -ares, -ires and -ries look like simple past or conditional endings.
        val plurals = mapOf(
            "cadires" to "cadira", "fronteres" to "frontera", "màscares" to "màscara", "teories" to "teoria",
            "carreteres" to "carretera", "guspires" to "guspira",
        )
        for (morphology in listOf(withList, withoutList)) {
            plurals.forEach { (plural, singular) ->
                assertEquals(plural, emptySet<String>(), guessedVerbs(morphology, plural, "ca"))
                assertEquals(plural, setOf(singular), nouns(morphology, plural, "ca"))
            }
        }
    }

    @Test
    fun spanishPluralsWithVerbEndingsAreNouns() {
        // -aras (imperfect subjunctive) and -amos (first person plural).
        val plurals = mapOf("cámaras" to "cámara", "lámparas" to "lámpara", "préstamos" to "préstamo", "fronteras" to "frontera")
        for (morphology in listOf(withList, withoutList)) {
            plurals.forEach { (plural, singular) ->
                assertEquals(plural, emptySet<String>(), guessedVerbs(morphology, plural, "es"))
                assertEquals(plural, setOf(singular), nouns(morphology, plural, "es"))
            }
        }
    }

    @Test
    fun attestedUnknownVerbsAreStillGuessed() {
        // The infinitive is a listed form, or tagged as a verb.
        assertEquals(setOf("programar"), guessedVerbs(withList, "programaríem", "ca"))
        assertEquals(setOf("bloquear"), guessedVerbs(withList, "bloquearon", "es"))
        // Another form of the verb that couldn't be a noun is listed.
        assertEquals(setOf("enxampar"), guessedVerbs(withList, "enxamparen", "ca"))
        assertEquals(setOf("editar"), guessedVerbs(withList, "editaron", "es"))
        // An attested verb whose form also looks plural keeps both readings.
        val programaves = withList.analyse("programaves", "ca")
        assertTrue(programaves.toString(), programaves.any { it.pos == PartOfSpeech.Verb && it.lemma == "programar" })
        assertTrue(programaves.toString(), programaves.any { it.pos == PartOfSpeech.Noun })
    }

    @Test
    fun unattestedVerbsAreNotGuessedWhenThereIsAList() {
        // No listed form of "chatear": with a list, the guess needs evidence.
        assertEquals(emptySet<String>(), guessedVerbs(withList, "chatearon", "es"))
        // Without a list, a guess no noun competes with stays.
        assertEquals(setOf("chatear"), guessedVerbs(withoutList, "chatearon", "es"))
        assertEquals(setOf("programar"), guessedVerbs(withoutList, "programaron", "es"))
    }

    @Test
    fun theWordAndItsOwnSingularAreNotEvidence() {
        // "maneres" and "manera" are both listed, yet neither is a form only a verb could have.
        assertTrue(evidence.getValue("ca").hasForm("maneres") && evidence.getValue("ca").hasForm("manera"))
        assertEquals(emptySet<String>(), guessedVerbs(withList, "maneres", "ca"))
        // "cámaras" and "cámara" don't make a verb "cámar" either.
        assertEquals(emptySet<String>(), guessedVerbs(withList, "cámaras", "es"))
    }

    @Test
    fun knownReadingsAreUnchanged() {
        val sparse = Morphology { VerbEvidence(emptyList(), listOf("x")) }
        val verbs = mapOf("ca" to listOf("cantar", "beure", "témer", "dormir", "servir"), "es" to listOf("hablar", "comer", "vivir", "pedir"))
        verbs.forEach { (language, lemmas) ->
            lemmas.forEach { lemma ->
                withoutList.paradigm(lemma, language).forEach { form ->
                    val before = withoutList.analyse(form.form, language)
                    assertEquals(form.form, before, sparse.analyse(form.form, language))
                    assertEquals(form.form, before, withList.analyse(form.form, language))
                }
            }
        }
        // Known simple pasts in -eres and -ares are still verbs: "perderes", "cantares".
        assertTrue(withList.verbs("perderes", "ca").any { it.lemma == "perdre" && !it.guessed })
        assertTrue(withList.verbs("cantares", "ca").any { it.lemma == "cantar" && !it.guessed })
    }

    @Test
    fun theConjugationMapNoLongerListsTheFalseVerb() {
        val utterance = Utterance("check:1", "check", "ca", "Hi ha moltes maneres de cuinar, i tinc tres cadires noves.", emptyList(), 0L)
        for (morphology in listOf(withList, withoutList)) {
            val report = ConjugationMap.build(listOf(utterance), "ca", morphology, ZoneOffset.UTC)
            val lemmas = report.verbs.map { it.lemma }
            assertTrue(lemmas.toString(), "manre" !in lemmas && "cadir" !in lemmas)
            assertTrue(lemmas.toString(), "tenir" in lemmas)
            assertTrue(report.cells.values.none { cell -> cell.verbs.keys.any { it == "manre" } })
        }
    }
}
