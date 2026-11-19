package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.MorphologyLemmatizer
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The partner's pool of targets: content words a conversation can lead to, in frequency order;
 * every rare everyday cell; registers named in the conversation's language; one language only;
 * and a bounded block.
 */
class PracticeTargetsPoolTest {
    private val bundled = BundledDecks { File("src/main/assets/$it").readText() }
    private val morphology = Morphology { bundled.verbEvidence(it) }
    private val lemmatizer = MorphologyLemmatizer(morphology)
    private var next = 0

    private fun said(text: String, language: String, origin: String = "menu") =
        Utterance("check:${next++}", "check", language, text, at = next.toLong(), origin = origin)

    private val spanishWriter = listOf(
        "Hoy trabajo en casa y como con mi hermana.", "Mi hermana vive en Madrid con su novio.",
        "Me gusta mucho leer por la noche.", "El lunes voy al gimnasio con un amigo.",
        "No tengo tiempo para cocinar esta semana.", "Mi jefe es simpático pero habla mucho.",
        "Compro pan en la panadería de la esquina.", "Los niños juegan en el parque después del colegio.",
        "Ayer comí paella con mis padres.", "Quiero aprender a nadar este verano.",
    ).map { said(it, "es") }

    private val catalanWriter = listOf(
        "Avui treballo a casa i dino amb la meva germana.", "La meva germana viu a Girona amb el seu nòvio.",
        "M'agrada molt llegir a la nit.", "Dilluns vaig al gimnàs amb un amic.",
        "No tinc temps per cuinar aquesta setmana.", "El meu cap és simpàtic però parla molt.",
        "Compro pa a la fleca de la cantonada.", "Els nens juguen al parc després de l'escola.",
        "Ahir vaig dinar amb els meus pares.", "Vull aprendre a nedar aquest estiu.",
    ).map { said(it, "ca") }

    private fun deck(language: String) = bundled.deck(language, lemmatizer)!!

    @Test fun spanishWordsAreContentWordsInFrequencyOrder() {
        val es = VocabDeck("es-test", "", "es", listOf(
            DeckEntry(1, "de", "prep"), DeckEntry(2, "ya", "adv"), DeckEntry(3, "más", "adv"), DeckEntry(4, "mismo", "adj"),
            DeckEntry(5, "tres", "num"), DeckEntry(6, "john", "other"), DeckEntry(7, "ir", "v"), DeckEntry(8, "casa", "n"),
            DeckEntry(9, "así", "adv"), DeckEntry(10, "comer", "v"), DeckEntry(11, "bonito", "adj"), DeckEntry(12, "viaje", "n"),
            DeckEntry(13, "todo", "det"), DeckEntry(14, "rápidamente", "adv"),
        ), bundled = false)
        assertEquals(listOf("casa", "comer", "bonito", "viaje"), PracticeTargetsBuilder.conversationWords(es, emptySet(), 4).map { it.lemma })
        // Too few content words left: adverbs that aren't function words fill in, still in deck order.
        assertEquals(listOf("casa", "comer", "bonito", "viaje", "rápidamente"),
            PracticeTargetsBuilder.conversationWords(es, setOf(), 5).map { it.lemma })
    }

    @Test fun catalanFormsGroupedByMorphologySkipFunctionWords() {
        val forms = listOf("més", "així", "mateix", "ja", "que", "casa", "menjar", "viatge", "bonic", "tres", "mai")
            .mapIndexed { index, form -> DeckEntry(index + 1, form, count = 1000L - index) }
        val ca = VocabDeck.fromForms("ca-test", "", "ca", forms, bundled = false) { lemmatizer.lexemes(it, "ca") }
        val picked = PracticeTargetsBuilder.conversationWords(ca, emptySet(), 4).map { it.lemma }
        assertEquals(listOf("casa", "menjar", "viatge", "bonic"), picked)
    }

    @Test fun theBundledDecksGiveThirtyContentWordsAtTheTop() {
        for (language in BundledDecks.languages) {
            val words = PracticeTargetsBuilder.conversationWords(deck(language), emptySet(), PracticeTargetsBuilder.MAX_WORDS)
            assertEquals(language, PracticeTargetsBuilder.MAX_WORDS, words.size)
            assertEquals(language, words.map { it.rank }.sorted(), words.map { it.rank })
            assertEquals(language, words.size, words.map { it.lemma }.toSet().size)
            assertTrue(language, words.all { Pos.of(it.pos) in setOf(Pos.Noun, Pos.Verb, Pos.Adjective) && it.lemma.length >= 3 })
            val lemmas = words.map { it.lemma }
            for (function in listOf("més", "així", "mateix", "ja", "ya", "mismo", "así", "más", "ser", "haber", "haver")) {
                assertFalse("$language: $function", function in lemmas)
            }
        }
    }

    @Test fun everyRareEverydayCellIsInThePoolMostUsefulFirst() {
        val targets = PracticeTargetsBuilder.build(spanishWriter, "es", morphology, deck("es"), lemmatizer)
        // Many cells, not one per tense; nothing the writer wrote (comí: preterite, I).
        assertTrue(targets.cells.size > 8)
        assertFalse(Cell(Tense.Preterite, Person.S1) in targets.cells)
        assertTrue(Cell(Tense.Future, Person.S1) in targets.cells && Cell(Tense.Future, Person.P1) in targets.cells)
        val order = listOf(Tense.Preterite, Tense.Imperfect, Tense.Future, Tense.Conditional, Tense.PresentSubjunctive,
            Tense.ImperfectSubjunctive, Tense.Imperative, Tense.Perfect)
        val tenses = targets.cells.map { it.tense }.distinct()
        assertEquals(order.filter { it in tenses }, tenses)
        // Catalan steers to "vaig anar", not the literary simple preterite.
        val ca = PracticeTargetsBuilder.build(catalanWriter, "ca", morphology, deck("ca"), lemmatizer)
        assertTrue(ca.cells.none { it.tense == Tense.Preterite })
        assertFalse(Cell(Tense.PeriphrasticPast, Person.S1) in ca.cells)
        assertTrue(ca.cells.any { it.tense == Tense.Future })
    }

    @Test fun theRegisterLineNamesOnlyTheConversationsLanguage() {
        assertEquals("formal writing (usted, polite requests, a message to an office or a landlord)", TargetRegister.Formal.prompt("es"))
        assertEquals("formal writing (vostè, polite requests, a message to an office or a landlord)", TargetRegister.Formal.prompt("ca"))
        assertEquals("formal writing (the polite form of address, polite requests, a message to an office or a landlord)",
            TargetRegister.Formal.prompt("fr"))
        val ca = PracticeTargets("ca", registers = listOf(TargetRegister.Formal)).render()
        assertTrue(ca.contains("vostè") && !ca.contains("usted"))
        val es = PracticeTargets("es", registers = listOf(TargetRegister.Formal)).render()
        assertTrue(es.contains("usted") && !es.contains("vostè"))
    }

    @Test fun theBlockHoldsOnlyTheConversationsLanguage() {
        // One writer in both languages; plenty of Spanish future and formal writing.
        val spanishFuture = (1..10).map { said("Estimado señor: mañana iré a la playa y viajaré con usted.", "es") }
        val corpus = catalanWriter + spanishWriter + spanishFuture
        val ca = PracticeTargetsBuilder.build(corpus, "ca", morphology, deck("ca"), lemmatizer)
        val block = ca.render()
        // Spanish writing says nothing about Catalan: its future, its formal writing, its words.
        assertTrue(Cell(Tense.Future, Person.S1) in ca.cells)
        assertTrue(TargetRegister.Formal in ca.registers)
        assertFalse(block.contains("usted"))
        val catalanDeck = deck("ca").entries.mapTo(HashSet()) { it.lemma }
        assertTrue(ca.words.all { it.lemma in catalanDeck })
        val spanishOnly = deck("es").entries.map { it.lemma }.toSet() - catalanDeck
        assertTrue(ca.words.none { it.lemma in spanishOnly })
        // Another language's deck is never used.
        assertTrue(PracticeTargetsBuilder.build(corpus, "ca", morphology, deck("es"), lemmatizer).words.isEmpty())
        val es = PracticeTargetsBuilder.build(corpus, "es", morphology, deck("es"), lemmatizer).render()
        assertFalse(es.contains("vostè") || es.contains("vaig"))
    }

    @Test fun theBlockForARealisticWriterIsBounded() {
        for ((language, writer) in listOf("es" to spanishWriter, "ca" to catalanWriter)) {
            val targets = PracticeTargetsBuilder.build(writer, language, morphology, deck(language), lemmatizer)
            val block = targets.render()
            assertEquals(language, PracticeTargetsBuilder.MAX_WORDS, targets.words.size)
            assertTrue("$language: ${block.length}", block.length in 400..PracticeTargetsBuilder.MAX_BLOCK)
            assertEquals(3, block.lines().size)
        }
        // Even the largest pool (every cell, long glosses) stays bounded.
        val cells = Tense.entries.flatMap { tense -> Person.entries.map { Cell(tense, it) } }
        val huge = PracticeTargets("es", cells, (1..PracticeTargetsBuilder.MAX_WORDS).map { TargetWord("palabra$it", "x".repeat(80)) },
            TargetRegister.entries)
        assertTrue(huge.render().length <= PracticeTargetsBuilder.MAX_BLOCK)
    }

    @Test fun anImportedDeckWithoutPartsOfSpeechStillGivesWords() {
        val plain = VocabDeck("es-plain", "", "es", listOf(DeckEntry(1, "de"), DeckEntry(2, "ya"), DeckEntry(3, "playa"), DeckEntry(4, "tren")),
            bundled = false)
        assertEquals(listOf("playa", "tren"), PracticeTargetsBuilder.conversationWords(plain, emptySet()).map { it.lemma })
    }
}
