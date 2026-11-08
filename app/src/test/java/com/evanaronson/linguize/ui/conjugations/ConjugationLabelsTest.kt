package com.evanaronson.linguize.ui.conjugations

import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class ConjugationLabelsTest {
    @Test
    fun usesAreFormattedWithAtMostOneDecimal() {
        assertEquals("3", formatUses(3.0, Locale.US))
        assertEquals("2.5", formatUses(2.5, Locale.US))
        assertEquals("0.3", formatUses(1.0 / 3, Locale.US))
        assertEquals("2,5", formatUses(2.5, Locale.forLanguageTag("ca")))
        assertEquals(1, usesQuantity(1.0))
        assertEquals(2, usesQuantity(0.5))
        assertEquals(4, usesQuantity(4.0))
    }

    @Test
    fun everyTenseAndPersonHasALabel() {
        Tense.entries.forEach { tense -> listOf("es", "ca").forEach { assertNotEquals(0, tense.labelResource(it)) } }
        assertNotEquals(Tense.Preterite.labelResource("es"), Tense.Preterite.labelResource("ca"))
        Person.entries.forEach { assertNotEquals(0, it.labelResource) }
        assertEquals("jo", Person.S1.pronoun("ca"))
        assertEquals("ellos, ellas, ustedes", Person.P3.pronouns("es"))
    }

    @Test
    fun theDefaultLanguageIsTheOneWithMostText() {
        val morphology = Morphology()
        val utterances = listOf(
            Utterance("check:1", "check", "ca", "Vaig anar a casa.", at = 1),
            Utterance("check:2", "check", "ca", "Tinc gana.", at = 2),
            Utterance("check:3", "check", "es", "Tengo hambre.", at = 3),
        )
        val reports = listOf("es", "ca").associateWith { ConjugationMap.build(utterances, it, morphology) }
        assertEquals("ca", defaultLanguage(reports))
        assertEquals("es", defaultLanguage(emptyMap()))
        assertEquals("ca", ConjugationsState(reports = reports).shown)
        assertEquals("es", ConjugationsState(reports = reports, language = "es").shown)
    }
}
