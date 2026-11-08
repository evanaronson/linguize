package com.evanaronson.linguize.core.morphology

import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphologyLemmatizerTest {
    private val lemmatizer = MorphologyLemmatizer(SharedMorphology.instance)

    private fun lexemes(word: String, language: String) = lemmatizer.lexemes(word, language)

    @Test
    fun verbFormsBecomeTheirInfinitive() {
        assertEquals(setOf(Lexeme("ir", Pos.Verb), Lexeme("ser", Pos.Verb)), lexemes("fuimos", "es"))
        assertEquals(setOf("anar"), lemmatizer.lemmas("vaig", "ca"))
        assertEquals(setOf("anar"), lemmatizer.lemmas("anem", "ca"))
        assertEquals(setOf("anar"), lemmatizer.lemmas("anar", "ca"))
        assertEquals(setOf("tener"), lemmatizer.lemmas("tuviéramos", "es"))
        assertEquals(setOf("decir"), lemmatizer.lemmas("dímelo", "es"))
        assertEquals(setOf("donar"), lemmatizer.lemmas("dóna-m'ho", "ca"))
    }

    @Test
    fun homographsKeepEveryLexeme() {
        assertEquals(setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun)), lexemes("la", "es"))
        assertEquals(setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun)), lexemes("la", "ca"))
        assertEquals(setOf(Lexeme("el", Pos.Article), Lexeme("ell", Pos.Pronoun)), lexemes("els", "ca"))
        assertEquals(setOf(Lexeme("de", Pos.Preposition), Lexeme("el", Pos.Article)), lexemes("del", "es"))
        assertTrue(Lexeme("él", Pos.Pronoun) in lexemes("lo", "es"))
        assertEquals(setOf(Lexeme("mío", Pos.Determiner)), lexemes("mis", "es"))
    }

    @Test
    fun wordsThatAreRarelyVerbsKeepThemselves() {
        assertEquals(setOf(Lexeme("como", Pos.Conjunction)), lexemes("como", "es"))
        assertEquals(setOf(Lexeme("para", Pos.Preposition)), lexemes("para", "es"))
        assertEquals(setOf(Lexeme("casa", Pos.Noun)), lexemes("casa", "es"))
        assertEquals(setOf(Lexeme("trabajar", Pos.Verb), Lexeme("trabajo", Pos.Noun)), lexemes("trabajo", "es"))
        assertEquals(setOf(Lexeme("donar", Pos.Verb), Lexeme("dona", Pos.Noun)), lexemes("dona", "ca"))
    }

    @Test
    fun adjectivesAndPluralsFold() {
        assertEquals(setOf(Lexeme("bueno", Pos.Adjective)), lexemes("buenas", "es"))
        assertEquals(setOf(Lexeme("content", Pos.Adjective)), lexemes("contentes", "ca"))
        assertEquals(setOf(Lexeme("ciudad", Pos.Noun)), lexemes("ciudades", "es"))
        assertTrue(Lexeme("cansado", Pos.Adjective) in lexemes("cansada", "es"))
        assertTrue(Lexeme("cansar", Pos.Verb) in lexemes("cansada", "es"))
    }

    @Test
    fun adverbsInMentAreTheirOwnLemma() {
        assertEquals(setOf(Lexeme("realmente", Pos.Adverb)), lexemes("realmente", "es"))
        assertEquals(setOf(Lexeme("realment", Pos.Adverb)), lexemes("realment", "ca"))
        assertEquals(setOf(Lexeme("exactament", Pos.Adverb)), lexemes("exactament", "ca"))
        // Catalan nouns in -ment are not adverbs.
        assertEquals(setOf(Lexeme("casament", Pos.Unknown)), lexemes("casament", "ca"))
        assertEquals(setOf(Lexeme("moment", Pos.Unknown)), lexemes("moment", "ca"))
    }

    @Test
    fun guessesAreUsedOnlyWhenNothingIsKnown() {
        assertEquals(setOf(Lexeme("programar", Pos.Verb)), lexemes("programaron", "es"))
        // A guessed gerund is not trusted: comando stays itself.
        assertEquals(setOf(Lexeme("comando", Pos.Unknown)), lexemes("comando", "es"))
    }

    @Test
    fun unknownWordsAndLanguagesAreThemselves() {
        assertEquals(setOf(Lexeme("xyzzy", Pos.Unknown)), lexemes("xyzzy", "es"))
        assertEquals(setOf(Lexeme("walked", Pos.Unknown)), lexemes("walked", "en"))
        assertEquals(setOf("ir", "ser"), lemmatizer.lemmas("Fuimos", "es"))
    }

    @Test
    fun anExtraTableFillsWhatTheMorphologyOnlyGuesses() {
        val extra = MorphologyLemmatizer(SharedMorphology.instance) { language ->
            if (language == "es") mapOf("comando" to setOf(Lexeme("comando", Pos.Noun))) else emptyMap()
        }
        assertEquals(setOf(Lexeme("comando", Pos.Noun)), extra.lexemes("comando", "es"))
        // Known readings still win.
        assertEquals(setOf("ir", "ser"), extra.lemmas("fuimos", "es"))
    }
}
