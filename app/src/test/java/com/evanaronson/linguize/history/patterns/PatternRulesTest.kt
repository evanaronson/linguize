package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.core.EditKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternRulesTest {
    private fun kind(from: String, to: String, why: String? = null, language: String? = "es", edit: EditKind = EditKind.Fix) =
        PatternRules.classify(from, to, why, language, edit)

    private fun assertGroup(expected: PatternKind, basis: Basis, from: String, to: String, why: String? = null, language: String? = "es") {
        assertEquals("$from -> $to", Classification(expected, basis), kind(from, to, why, language))
    }

    @Test
    fun diacriticOnlyChangesAreAccentsAsAFact() {
        assertGroup(PatternKind.Accents, Basis.Exact, "esta", "está")
        assertGroup(PatternKind.Accents, Basis.Exact, "Tambien", "También")
        assertGroup(PatternKind.Accents, Basis.Exact, "cami", "camí", language = "ca")
        assertGroup(PatternKind.Accents, Basis.Exact, "pinguino", "pingüino")
        assertGroup(PatternKind.Accents, Basis.Exact, "más", "mas")
        // A capital and an accent at once is still about the accent.
        assertGroup(PatternKind.Accents, Basis.Exact, "aqui", "Aquí")
    }

    @Test
    fun accentsWinOverAMisleadingReason() {
        assertGroup(PatternKind.Accents, Basis.Exact, "tenia", "tenía", why = "Imperfect tense needs an accent")
    }

    @Test
    fun composedAndDecomposedAccentsCompareEqual() {
        // "está" with a combining acute (NFD) and with a precomposed á (NFC) is no change at all.
        assertNull(kind("está", "está"))
    }

    @Test
    fun capitalsAndPunctuationAreFacts() {
        assertGroup(PatternKind.Capitals, Basis.Exact, "barcelona", "Barcelona")
        assertGroup(PatternKind.Punctuation, Basis.Exact, "Hola que tal", "Hola, que tal")
        assertGroup(PatternKind.Punctuation, Basis.Exact, "ok .", "ok.")
    }

    @Test
    fun openingMarksAreTheirOwnGroupInSpanish() {
        assertGroup(PatternKind.InvertedMarks, Basis.Exact, "Vienes?", "¿Vienes?")
        assertGroup(PatternKind.InvertedMarks, Basis.Exact, "Qué bien!", "¡Qué bien!")
        // Catalan doesn't use them: the same change is plain punctuation.
        assertGroup(PatternKind.Punctuation, Basis.Exact, "Vens?", "¿Vens?", language = "ca")
    }

    @Test
    fun spacingOnlyAndIdenticalTextAreNotCounted() {
        assertNull(kind("hola", "hola"))
        assertNull(kind(" hola  amigo", "hola amigo"))
    }

    @Test
    fun serAndEstarSwapsEitherWay() {
        assertGroup(PatternKind.SerEstar, Basis.Words, "es cansado", "está cansado")
        assertGroup(PatternKind.SerEstar, Basis.Words, "Estoy profesor", "Soy profesor")
        assertGroup(PatternKind.SerEstar, Basis.Words, "era muy lejos", "estaba muy lejos")
        assertGroup(PatternKind.SerEstar, Basis.Words, "és cansat", "està cansat", language = "ca")
    }

    @Test
    fun porAndParaOnlyInSpanish() {
        assertGroup(PatternKind.PorPara, Basis.Words, "gracias para todo", "gracias por todo")
        assertGroup(PatternKind.PorPara, Basis.Words, "por ti", "para ti", language = null)
    }

    @Test
    fun catalanTenirQueBecomesHaverDe() {
        assertGroup(PatternKind.TenirQue, Basis.Words, "tinc que anar", "he d'anar", language = "ca")
        assertGroup(PatternKind.TenirQue, Basis.Words, "Tenim que parlar", "Hem de parlar", language = "ca")
        assertGroup(PatternKind.TenirQue, Basis.Words, "té que venir", "cal que vingui", language = "ca")
        // Auto Detect with no language still recognises it.
        assertGroup(PatternKind.TenirQue, Basis.Words, "tenia que treballar", "havia de treballar", language = null)
    }

    @Test
    fun tenirQueIsNotLookedForInSpanish() {
        // Spanish "tengo que" is correct; a Spanish text never lands in the Catalan group.
        val result = kind("tinc que anar", "he d'anar", language = "es")
        assert(result?.kind != PatternKind.TenirQue)
    }

    @Test
    fun catalanElision() {
        assertGroup(PatternKind.Elision, Basis.Words, "la amiga", "l'amiga", language = "ca")
        assertGroup(PatternKind.Elision, Basis.Words, "de acord", "d'acord", language = "ca")
        assertGroup(PatternKind.Elision, Basis.Words, "el home", "l’home", language = "ca")
    }

    @Test
    fun genderAgreement() {
        assertGroup(PatternKind.Gender, Basis.Words, "la problema", "el problema")
        assertGroup(PatternKind.Gender, Basis.Words, "una día bueno", "un día bueno")
        assertGroup(PatternKind.Gender, Basis.Words, "muy contento", "muy contenta")
        assertGroup(PatternKind.Gender, Basis.Words, "los manos frías", "las manos frías")
        assertGroup(PatternKind.Gender, Basis.Words, "estic content", "estic contenta", language = "ca")
        assertGroup(PatternKind.Gender, Basis.Words, "aquest casa", "aquesta casa", language = "ca")
    }

    @Test
    fun singularAndPlural() {
        assertGroup(PatternKind.Number, Basis.Words, "los niño", "los niños")
        assertGroup(PatternKind.Number, Basis.Words, "el mes pasados", "el mes pasado")
        assertGroup(PatternKind.Number, Basis.Words, "dos ciudad", "dos ciudades")
        assertGroup(PatternKind.Number, Basis.Words, "la gente están", "la gente está")
    }

    @Test
    fun articlesAndPrepositions() {
        assertGroup(PatternKind.Articles, Basis.Words, "voy a playa", "voy a la playa")
        assertGroup(PatternKind.Prepositions, Basis.Words, "pienso en ti", "pienso de ti")
        assertGroup(PatternKind.Prepositions, Basis.Words, "veo María", "veo a María")
        assertGroup(PatternKind.Prepositions, Basis.Words, "vaig a Girona amb tren", "vaig a Girona en tren", language = "ca")
        assertGroup(PatternKind.Prepositions, Basis.Words, "depende en ti", "depende de ti")
    }

    @Test
    fun reasonKeywordsWhenTheWordsDontSay() {
        assertGroup(PatternKind.Subjunctive, Basis.Reason, "cuando llegas", "cuando llegues",
            why = "After cuando about the future, use the subjunctive.")
        assertGroup(PatternKind.VerbForms, Basis.Reason, "ayer fui cansado", "ayer iba cansado",
            why = "Use the imperfect for descriptions in the past.")
        assertGroup(PatternKind.VerbForms, Basis.Reason, "Ayer como", "Ayer comí", why = "Pretérito perfecto simple.")
        assertGroup(PatternKind.Agreement, Basis.Reason, "Me gusta los perros", "Me gustan los perros",
            why = "The verb must agree with los perros.")
        assertGroup(PatternKind.Spelling, Basis.Reason, "haber si vienes", "a ver si vienes", why = "Common spelling confusion.")
    }

    @Test
    fun reasonsInSpanishAndCatalanToo() {
        assertGroup(PatternKind.Subjunctive, Basis.Reason, "quiero que vienes", "quiero que vengas",
            why = "Después de querer que va el subjuntivo.")
        assertGroup(PatternKind.Subjunctive, Basis.Reason, "quan arribes", "quan arribis", language = "ca",
            why = "Cal el subjuntiu després de quan.")
        assertGroup(PatternKind.Gender, Basis.Reason, "es bonic", "es maca", language = "ca",
            why = "Cal la forma de gènere femení.")
    }

    @Test
    fun aReasonNeverOverridesWhatTheTextProves() {
        assertGroup(PatternKind.Capitals, Basis.Exact, "lunes", "Lunes", why = "Spelling: proper noun")
    }

    @Test
    fun nearMissesAreSpelling() {
        assertGroup(PatternKind.Spelling, Basis.Words, "aver", "haber")
        assertGroup(PatternKind.Spelling, Basis.Words, "nesecito", "necesito")
    }

    @Test
    fun unrelatedWordsAreOther() {
        assertEquals(Classification(PatternKind.Other, Basis.Words), kind("coger el bus", "tomar el bus"))
    }

    @Test
    fun rewordingsOnlyCountWhenASpecificRuleMatches() {
        assertNull(kind("coger el bus", "tomar el bus", edit = EditKind.Natural))
        assertNull(kind("nesecito", "necesito", edit = EditKind.Natural))
        assertEquals(
            Classification(PatternKind.TenirQue, Basis.Words),
            kind("tinc que marxar", "he de marxar", language = "ca", edit = EditKind.Natural),
        )
    }

    @Test
    fun classificationIsDeterministic() {
        val pairs = listOf("es cansado" to "está cansado", "la problema" to "el problema", "aver" to "haber")
        val first = pairs.map { kind(it.first, it.second) }
        repeat(5) { assertEquals(first, pairs.map { kind(it.first, it.second) }) }
    }

    @Test
    fun wordDiffKeepsTheSharedRun() {
        val change = wordChange(words("voy a la playa hoy"), words("voy a playa mañana"))
        assertEquals(listOf("la", "hoy"), change.removed)
        assertEquals(listOf("mañana"), change.added)
    }

    @Test
    fun wordsSplitElidedClitics() {
        assertEquals(listOf("l'", "home", "d'", "acord"), words("L'home d’acord"))
        assertEquals(listOf("qué", "tal"), words("¿Qué tal?"))
    }

    @Test
    fun foldAndLevenshtein() {
        assertEquals("pinguino", fold("Pingüino"))
        assertEquals("cami", fold("camí"))
        assertEquals(2, levenshtein("aver", "haber"))
        assertEquals(0, levenshtein("", ""))
        assertEquals(3, levenshtein("", "abc"))
    }
}
