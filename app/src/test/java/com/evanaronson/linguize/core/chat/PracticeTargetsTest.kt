package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.chat.ChatSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the partner steers toward, how it is bounded, and how it and a stand-in reach the prompt. */
class PracticeTargetsTest {
    private val morphology = Morphology()
    private var next = 0

    private fun said(text: String, origin: String = "menu", language: String = "es") =
        Utterance("check:${next++}", "check", language, text, at = next.toLong(), origin = origin)

    /** Present-tense chatter: plenty of writing, never a future, conditional or subjunctive. */
    private val present = (1..12).map { said("Hoy trabajo en casa y como con mi hermana, luego leo un libro.") }

    private val deck = VocabDeck("es-test", "", "es", listOf(
        DeckEntry(1, "casa"), DeckEntry(2, "viaje", gloss = "trip"), DeckEntry(3, "trabajo"), DeckEntry(4, "playa", gloss = "beach"),
    ), bundled = false)

    @Test fun rareVerbFormsUnusedWordsAndSeldomRegistersAreFound() {
        val targets = PracticeTargetsBuilder.build(present, "es", morphology, deck, Lemmatizer.Identity)
        assertTrue(targets.cells.isNotEmpty())
        assertTrue(targets.cells.size <= PracticeTargetsBuilder.MAX_CELLS)
        assertTrue(targets.cells.none { it.tense == Tense.Present })
        assertTrue(Cell(Tense.Future, Person.S1) in targets.cells || targets.cells.any { it.tense == Tense.Preterite })
        assertEquals(listOf("viaje", "playa"), targets.words.map { it.lemma })
        // No usted, no "jaja": both registers are seldom written.
        assertEquals(listOf(TargetRegister.Formal, TargetRegister.Casual), targets.registers)
    }

    @Test fun formalWritingInTheCorpusTakesFormalOffTheList() {
        val formal = present + (1..4).map { said("Estimado señor, le escribo para pedirle una cita. Atentamente.") }
        assertFalse(TargetRegister.Formal in PracticeTargetsBuilder.registerGaps(formal, "es"))
    }

    @Test fun chatWritingCountsHoweverMuchOtherWritingThereIs() {
        // Plenty of writing elsewhere: chat still counts, as in Words and Conjugations.
        val real = (1..60).map { said("Hoy trabajo en casa.") }
        val before = PracticeTargetsBuilder.build(real, "es", morphology, deck, Lemmatizer.Identity)
        assertTrue("playa" in before.words.map { it.lemma })
        assertTrue(Cell(Tense.Future, Person.S1) in before.cells)
        val chat = listOf(said("Mañana iré a la playa.", origin = "chat"))
        val after = PracticeTargetsBuilder.build(real + chat, "es", morphology, deck, Lemmatizer.Identity)
        assertFalse("playa" in after.words.map { it.lemma })
        assertFalse(Cell(Tense.Future, Person.S1) in after.cells)
    }

    @Test fun aCorrectedChatUseDoesntCount() {
        val real = (1..60).map { said("Hoy trabajo en casa.") }
        val text = "Mañana iré a la playa."
        val flagged = Utterance("check:flagged", "check", "es", text, at = 999,
            corrected = listOf(Span(0, text.length)), origin = "chat")
        val targets = PracticeTargetsBuilder.build(real + flagged, "es", morphology, deck, Lemmatizer.Identity)
        assertTrue("playa" in targets.words.map { it.lemma })
        assertTrue(Cell(Tense.Future, Person.S1) in targets.cells)
    }

    @Test fun tooLittleWritingSaysNothingAboutFormsOrRegisters() {
        val targets = PracticeTargetsBuilder.build(present.take(2), "es", morphology, null, Lemmatizer.Identity)
        assertTrue(targets.isEmpty)
        assertEquals("", targets.render())
        assertTrue(PracticeTargetsBuilder.build(present, "ca", morphology, deck, Lemmatizer.Identity).isEmpty)
    }

    @Test fun theBlockIsShortPlainAndBounded() {
        val block = PracticeTargetsBuilder.build(present, "es", morphology, deck, Lemmatizer.Identity).render()
        assertTrue(block.startsWith("- Verb forms they rarely or never write: "))
        assertTrue(block.contains("viaje (trip)"))
        assertTrue(block.contains("formal writing"))
        assertTrue(block.length <= PracticeTargetsBuilder.MAX_BLOCK)
        val huge = PracticeTargets("es", words = (1..500).map { TargetWord("palabra$it", "x".repeat(80)) })
        assertTrue(huge.render().length <= PracticeTargetsBuilder.MAX_BLOCK)
    }

    @Test fun theFingerprintChangesWithTheEvidence() {
        val one = PracticeTargetsBuilder.fingerprint(present, "es")
        assertEquals(one, PracticeTargetsBuilder.fingerprint(present.reversed(), "es"))
        assertNotEquals(one, PracticeTargetsBuilder.fingerprint(present.drop(1), "es"))
        assertEquals(PracticeTargetsBuilder.fingerprint(emptyList(), "es"), PracticeTargetsBuilder.fingerprint(present, "ca"))
    }

    private val prompt = ChatPrompt.load { File("src/main/assets/$it").readText() }

    @Test fun targetsEndTheInstructionsWithTheSteeringRules() {
        val block = PracticeTargetsBuilder.build(present, "es", morphology, deck, Lemmatizer.Identity).render()
        val setting = ChatSetting("Spanish", "Peninsular", "English", memory = "Has a dog.", targets = block)
        val system = prompt.openRequest(setting).system
        assertTrue(system.contains("## What you remember about the learner\n\nHas a dog."))
        assertTrue(system.endsWith("$block\n"))
        // How to steer comes with the list, and only with it.
        assertTrue(system.contains("## Practice targets\n\nHere is what the learner could practise next"))
        assertTrue(system.contains("All the learner notices is a good conversation."))
        assertTrue(system.contains("Never comment on the learner's grammar"))
        val without = prompt.openRequest(setting.copy(targets = "")).system
        assertFalse(without.contains("## Practice targets"))
        assertFalse(without.contains("Lead the conversation toward these"))
    }

    @Test fun aStandInPlaysThePersonAndStillNeverCorrects() {
        val setting = ChatSetting("Catalan", "", "English", memory = "", persona = "Jordi, my landlord, formal")
        val user = Json.parseToJsonElement(prompt.openRequest(setting).user).jsonObject
        assertEquals("Jordi, my landlord, formal", user.getValue("persona").jsonPrimitive.content)
        assertEquals("", Json.parseToJsonElement(prompt.openRequest(setting.copy(persona = null)).user).jsonObject
            .getValue("persona").jsonPrimitive.content)
        val system = prompt.system("")
        assertTrue(system.contains("## Standing in for someone"))
        assertTrue(system.contains("Stay in role"))
        assertTrue(system.contains("You still never comment on or correct the learner's language"))
        val long = "x".repeat(ChatPrompt.MAX_PERSONA + 50)
        val sent = Json.parseToJsonElement(prompt.openRequest(setting.copy(persona = long)).user).jsonObject
        assertEquals(ChatPrompt.MAX_PERSONA, sent.getValue("persona").jsonPrimitive.content.length)
    }
}
