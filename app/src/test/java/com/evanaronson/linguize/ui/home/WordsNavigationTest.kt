package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordsNavigationTest {
    @Test
    fun wordScreensSurviveSavedState() {
        val path = Navigation().open(Screen.Progress).open(Screen.Words).open(Screen.WordDetail("ca", "l'", "f")).open(Screen.Detail("s1"))
        assertEquals(listOf("home", "progress", "words", "word:f:ca:l'", "detail:s1"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
    }

    @Test
    fun lexemesAndLemmasRoundTrip() {
        val lexeme = Screen.WordDetail("ca", "el/art", "x")
        assertEquals("word:x:ca:el/art", lexeme.saved)
        assertEquals(lexeme, Screen.restore(lexeme.saved))
        val lemma = Screen.WordDetail(null, "anar", "l")
        assertEquals(lemma, Screen.restore(lemma.saved))
    }

    @Test
    fun vocabularyGapsRestoresToWords() {
        assertEquals(Screen.Words, Screen.restore("vocab_gaps"))
        val restored = Navigation.restore(listOf("lab_chat", "progress", "vocab_gaps"))
        assertEquals(Screen.Words, restored.current)
        assertEquals(Screen.Progress, restored.back().current)
    }

    @Test
    fun deckRowKeysRoundTrip() {
        val row = Screen.WordDetail("es", "d:a menudo/adv", "x")
        assertEquals(row, Screen.restore(row.saved))
    }

    @Test
    fun anEmptyWordOrUnknownLevelIsNotRestored() {
        assertNull(Screen.restore("word:f:ca:"))
        assertNull(Screen.restore("word:"))
        assertNull(Screen.restore("word:z:ca:casa"))
    }
}
