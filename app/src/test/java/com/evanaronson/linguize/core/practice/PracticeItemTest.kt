package com.evanaronson.linguize.core.practice

import com.evanaronson.linguize.core.practice.PracticeItem.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeItemTest {
    @Test
    fun wordsAndShortPhrasesAreTyped() {
        assertEquals(Mode.Type, PracticeItem.modeOf("despues", "después"))
        assertEquals(Mode.Type, PracticeItem.modeOf("lo vaig veure", "el vaig veure"))
        assertEquals(Mode.Type, PracticeItem.modeOf("casa", "a casa"))
        // An accent with punctuation is still typed: the accent is the point.
        assertEquals(Mode.Type, PracticeItem.modeOf("que tal", "¿qué tal?"))
    }

    @Test
    fun deletionsPunctuationCapitalsAndLongRewritesArePicked() {
        assertEquals(Mode.Choose, PracticeItem.modeOf("de que", ""))
        assertEquals(Mode.Choose, PracticeItem.modeOf("si no", "si, no"))
        assertEquals(Mode.Choose, PracticeItem.modeOf("lunes", "Lunes"))
        assertEquals(Mode.Choose, PracticeItem.modeOf("tal", "tal?"))
        assertEquals(Mode.Choose, PracticeItem.modeOf("ir", "me gustaría mucho poder ir"))
        assertEquals(Mode.Choose, PracticeItem.modeOf("x", "extraordinariamente desproporcionadamente"))
    }

    @Test
    fun correctedReplacesTheWrongWords() {
        val item = PracticeItem("s", "c", 0, "es", "Nos vemos despues.", 10, 17, "después")
        assertEquals("despues", item.wrong)
        assertEquals("Nos vemos después.", item.corrected)
        assertEquals(Mode.Type, item.mode)
    }
}
