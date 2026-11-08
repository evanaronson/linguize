package com.evanaronson.linguize.core.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTokenizerTest {
    private fun forms(text: String) = WordTokenizer.tokenize(text).filter { !it.name }.map { it.form }
    private fun names(text: String) = WordTokenizer.tokenize(text).filter { it.name }.map { it.written }

    @Test
    fun splitsPlainSpanishWithAccentsAndEnye() {
        assertEquals(listOf("mañana", "voy", "a", "la", "montaña", "también"), forms("Mañana voy a la montaña, también."))
    }

    @Test
    fun keepsCatalanCedillaAndGeminateL() {
        assertEquals(listOf("la", "col·lecció", "de", "plaça"), forms("La col·lecció de plaça"))
    }

    @Test
    fun readsGeminateLTypedWithAFullStopOrBullet() {
        assertEquals(listOf("col·legi", "intel·ligent", "il·lusió"), forms("col.legi intel•ligent iŀlusió"))
    }

    @Test
    fun aFullStopBetweenOtherLettersEndsTheSentence() {
        val tokens = WordTokenizer.tokenize("Vaig anar.Després vaig sopar")
        assertEquals(listOf("vaig", "anar", "després", "vaig", "sopar"), tokens.map { it.form })
        assertTrue(tokens.none { it.name })
    }

    @Test
    fun splitsElidedArticlesAndPrepositions() {
        assertEquals(listOf("l'", "home", "d'", "aquí"), forms("l'home d'aquí"))
        assertEquals(listOf("m'", "agrada", "l'", "escola"), forms("M'agrada l’escola"))
        assertEquals(listOf("n'", "hi", "ha", "s'", "ha", "acabat"), forms("n'hi ha, s'ha acabat"))
    }

    @Test
    fun keepsSpansOfSplitPieces() {
        val text = "Vull l'home"
        val tokens = WordTokenizer.tokenize(text)
        assertEquals(listOf("Vull", "l'", "home"), tokens.map { text.substring(it.start, it.end) })
    }

    @Test
    fun splitsApostropheEncliticsFromTheVerb() {
        assertEquals(listOf("compra", "'l"), forms("compra'l"))
        assertEquals(listOf("porta", "'ls", "aquí"), forms("porta'ls aquí"))
    }

    @Test
    fun splitsHyphenatedEncliticsFromTheVerb() {
        assertEquals(listOf("digues", "li"), forms("digues-li"))
        assertEquals(listOf("dóna", "m'", "ho"), forms("dóna-m'ho"))
        assertEquals(listOf("anar", "se", "'n"), forms("anar-se'n"))
        assertEquals(listOf("fes", "ho", "ara"), forms("fes-ho ara"))
        assertEquals(listOf("posa", "t'", "hi"), forms("posa-t'hi"))
        assertEquals(listOf("dímelo", "y", "vete", "ya"), forms("dímelo y vete ya"))
    }

    @Test
    fun keepsOtherHyphenatedWordsWhole() {
        assertEquals(listOf("porta-avions"), forms("porta-avions"))
        assertEquals(listOf("un", "acuerdo", "franco-alemán"), forms("un acuerdo franco-alemán"))
    }

    @Test
    fun anElisionBeforeACompoundStillSplits() {
        assertEquals(listOf("l'", "auto-stop"), forms("l'auto-stop"))
    }

    @Test
    fun spanishCliticsAttachedWithoutHyphenStayOneWord() {
        assertEquals(listOf("dámelo", "ya"), forms("Dámelo ya"))
    }

    @Test
    fun leavesOutNumbersAndWordsWithDigits() {
        assertEquals(listOf("tinc", "anys", "i", "vinc", "el", "dia", "a", "les"), forms("Tinc 34 anys i vinc el 3r dia a les 10h"))
        assertEquals(listOf("cuesta", "euros"), forms("Cuesta 12,50 euros"))
    }

    @Test
    fun leavesOutLinksEmailsMentionsAndHashtags() {
        assertEquals(
            listOf("mira", "i", "escriu", "a", "o", "a"),
            forms("Mira https://example.com/a?b=c i escriu a maria@example.cat o a @joan_p #festa"),
        )
        assertEquals(listOf("entra", "a"), forms("Entra a www.ara.cat"))
        assertEquals(listOf("visita"), forms("Visita google.com."))
    }

    @Test
    fun leavesOutEmojiAndLaughter() {
        assertEquals(listOf("que", "bé"), forms("Que bé 😂😂 jajaja"))
        assertEquals(listOf("vale"), forms("jeje vale hahaha"))
    }

    @Test
    fun caseFoldsWordsAtTheStartOfASentence() {
        val tokens = WordTokenizer.tokenize("Hola. Gràcies! ¿Vienes? Sí")
        assertEquals(listOf("hola", "gràcies", "vienes", "sí"), tokens.map { it.form })
        assertTrue(tokens.none { it.name })
        assertTrue(tokens.all { it.capitalizedAtStart })
    }

    @Test
    fun capitalizedWordsInMidSentenceAreNames() {
        assertEquals(listOf("Marta", "Barcelona"), names("He vist la Marta a Barcelona"))
        assertEquals(listOf("he", "vist", "la", "a"), forms("He vist la Marta a Barcelona"))
    }

    @Test
    fun aNewLineStartsASentence() {
        val tokens = WordTokenizer.tokenize("hola\nQue tal")
        assertFalse(tokens.any { it.name })
    }

    @Test
    fun aQuestionAfterACommaMayStartWithACapital() {
        assertTrue(names("Oye, ¿Vienes mañana?").isEmpty())
    }

    @Test
    fun aCapitalAfterAnElidedArticleIsAName() {
        assertEquals(listOf("Hospitalet"), names("Visc a L'Hospitalet"))
        assertEquals(listOf("Hospitalet"), names("L'Hospitalet és a prop"))
    }

    @Test
    fun shoutingSaysNothingAboutNames() {
        assertTrue(names("NO PUEDO IR MAÑANA LO SIENTO").isEmpty())
        assertEquals(listOf("no", "puedo", "ir", "mañana", "lo", "siento"), forms("NO PUEDO IR MAÑANA LO SIENTO"))
    }

    @Test
    fun normalizesApostrophesAndComposedAccents() {
        val decomposed = "café"
        assertEquals(listOf("café"), forms(decomposed))
        assertEquals(WordTokenizer.normalize("l'"), WordTokenizer.normalize("L’"))
    }

    @Test
    fun quotesAndBracketsAreNotPartOfWords() {
        assertEquals(listOf("diu", "que", "sí", "i", "ja", "està"), forms("Diu \"que sí\" (i ja està)"))
        assertEquals(listOf("molt", "bé"), forms("«Molt bé»"))
    }
}
