package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordsNavigationTest {
    @Test
    fun wordScreensSurviveSavedState() {
        val path = Navigation().open(Screen.Words).open(Screen.WordDetail("ca", "l'", lemma = false)).open(Screen.Detail("s1"))
        assertEquals(listOf("home", "words", "word:f:ca:l'", "detail:s1"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
    }

    @Test
    fun aLemmaWithoutLanguageRoundTrips() {
        val screen = Screen.WordDetail(null, "anar", lemma = true)
        assertEquals(screen, Screen.restore(screen.saved))
    }

    @Test
    fun anEmptyWordIsNotRestored() {
        assertNull(Screen.restore("word:f:ca:"))
        assertNull(Screen.restore("word:"))
    }
}
