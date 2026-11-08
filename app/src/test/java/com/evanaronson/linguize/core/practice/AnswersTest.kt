package com.evanaronson.linguize.core.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswersTest {
    private fun check(original: String, expected: String, answer: String) = Answers.check(original, expected, answer)

    @Test
    fun exactAndNormalisedAnswersAreCorrect() {
        assertEquals(AnswerResult.Correct, check("despues", "después", "después"))
        assertEquals(AnswerResult.Correct, check("despues", "después", "  después  "))
        assertEquals(AnswerResult.Correct, check("lo vaig veure", "el vaig veure", "el   vaig\tveure"))
        // Typographic apostrophes are the same apostrophe.
        assertEquals(AnswerResult.Correct, check("la escola", "l'escola", "l’escola"))
        // Composed and decomposed accents are the same letter.
        assertEquals(AnswerResult.Correct, check("despues", "después", "después"))
    }

    @Test
    fun capitalsDontCountUnlessTheFixWasAboutThem() {
        assertEquals(AnswerResult.Correct, check("despues", "después", "Después"))
        assertEquals(AnswerResult.Correct, check("lunes", "Lunes", "Lunes"))
        assertEquals(AnswerResult.Unchanged, check("lunes", "Lunes", "lunes"))
        assertEquals(AnswerResult.Almost(Hint.Capitals), check("lunes", "Lunes", "LUNES"))
    }

    @Test
    fun theOriginalMistakeIsUnchanged() {
        assertEquals(AnswerResult.Unchanged, check("despues", "después", "despues"))
        assertEquals(AnswerResult.Unchanged, check("despues", "después", "Despues "))
        assertEquals(AnswerResult.Unchanged, check("a ver", "haber", "a ver"))
    }

    @Test
    fun missingAccentNamesTheWord() {
        assertEquals(
            AnswerResult.Almost(Hint.MissingAccent("después")),
            check("despues que llegas", "después de que llegues", "despues de que llegues"),
        )
    }

    @Test
    fun extraAccentNamesTheExpectedWord() {
        assertEquals(
            AnswerResult.Almost(Hint.ExtraAccent("como")),
            check("como que", "como", "cómo"),
        )
    }

    @Test
    fun aDifferentAccentIsAWrongAccent() {
        // Catalan grave against Spanish acute.
        assertEquals(AnswerResult.Almost(Hint.WrongAccent("què")), check("que", "què", "qué"))
        // The accent on another letter.
        assertEquals(AnswerResult.Almost(Hint.WrongAccent("també")), check("tambe", "també", "tàmbe"))
    }

    @Test
    fun severalAccentSlipsAreReportedTogether() {
        assertEquals(
            AnswerResult.Almost(Hint.Accents),
            check("despues que estas", "después de que estás", "despues de que estas"),
        )
    }

    @Test
    fun cedillaAndTildeCountAsAccents() {
        assertEquals(AnswerResult.Almost(Hint.MissingAccent("año")), check("ano pasado", "el año", "el ano"))
        assertEquals(AnswerResult.Almost(Hint.MissingAccent("començar")), check("comencar a", "començar", "comencar"))
    }

    @Test
    fun punctuationOnlyIsAlmost() {
        assertEquals(AnswerResult.Almost(Hint.Punctuation), check("que tal", "¿qué tal?", "qué tal"))
        assertEquals(AnswerResult.Almost(Hint.Punctuation), check("si no", "sí, no", "sí no"))
    }

    @Test
    fun oneLetterSlipCloserThanTheOriginalIsSpelling() {
        assertEquals(AnswerResult.Almost(Hint.Spelling), check("hechar", "echar de menos", "echar de menis"))
        // Too short to call a slip.
        assertEquals(AnswerResult.Wrong, check("le", "les", "lis"))
    }

    @Test
    fun aSlipNoCloserThanTheOriginalIsWrong() {
        // "haver" is one letter from "haber" too: the answer did not get closer.
        assertEquals(AnswerResult.Wrong, check("haver", "haber", "habes"))
    }

    @Test
    fun unrelatedAnswersAreWrong() {
        assertEquals(AnswerResult.Wrong, check("despues", "después", "luego"))
        assertEquals(AnswerResult.Wrong, check("lo vaig veure", "el vaig veure", "la vaig veure ahir"))
    }

    @Test
    fun itemCheckAcceptsTheWholeCorrectedSentence() {
        val item = PracticeItem("s", "c", 0, "es", "Nos vemos despues.", 10, 17, "después")
        assertEquals(AnswerResult.Correct, Answers.check(item, "después"))
        assertEquals(AnswerResult.Correct, Answers.check(item, "Nos vemos después."))
        assertEquals(AnswerResult.Unchanged, Answers.check(item, "despues"))
        assertEquals(AnswerResult.Wrong, Answers.check(item, "luego"))
    }

    @Test
    fun gradesFollowResults() {
        assertEquals(Grade.Good, AnswerResult.Correct.grade)
        assertEquals(Grade.Hard, AnswerResult.Almost(Hint.Punctuation).grade)
        assertEquals(Grade.Again, AnswerResult.Unchanged.grade)
        assertEquals(Grade.Again, AnswerResult.Wrong.grade)
    }

    @Test
    fun wordsKeepApostrophesHyphensAndPuntVolat() {
        assertEquals(listOf("l'escola", "col·legi", "dóna-m'ho"), Answers.words("l’escola, col·legi; dóna-m'ho!"))
        assertEquals("cella", Answers.stripAccents("cel·la"))
    }

    @Test
    fun distanceIsLevenshtein() {
        assertEquals(0, Answers.distance("casa", "casa"))
        assertEquals(1, Answers.distance("casa", "cosa"))
        assertEquals(1, Answers.distance("casa", "casas"))
        assertEquals(3, Answers.distance("", "abc"))
    }
}
