package com.evanaronson.linguize.ui.chat

import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.core.UnderstandingNote
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Translate on a partner message: idle, loading, shown or failed, kept per message. */
class TranslationsTest {
    private val asked = mutableListOf<String>()
    private var fail = false
    private val translations = Translations { text ->
        asked += text
        if (fail) throw CheckFailure(CheckFailure.Reason.Offline)
        Understood("How's your day going?", listOf(UnderstandingNote("¿Qué tal?", "A casual greeting")))
    }

    @Test fun idleThenLoadingThenShown() = runBlocking {
        assertNull(translations.shown("m1"))
        assertFalse(translations.isOpen("m1"))
        assertTrue(translations.toggle("m1"))
        assertEquals(Translation.Loading, translations.shown("m1"))
        translations.load("m1", "¿Qué tal tu día?")
        val shown = translations.shown("m1") as Translation.Shown
        assertEquals("How's your day going?", shown.understood.translation)
        assertEquals("A casual greeting", shown.understood.notes.single().explanation)
    }

    @Test fun hidingAndShowingAgainUsesTheKeptTranslation() = runBlocking {
        translations.toggle("m1")
        translations.load("m1", "Hola")
        assertFalse(translations.toggle("m1"))
        assertNull(translations.shown("m1"))
        assertFalse(translations.toggle("m1"))
        assertTrue(translations.shown("m1") is Translation.Shown)
        assertEquals(listOf("Hola"), asked)
    }

    @Test fun aFailureShowsAndTappingAgainRetries() = runBlocking {
        fail = true
        translations.toggle("m1")
        translations.load("m1", "Hola")
        assertEquals(CheckFailure.Reason.Offline, (translations.shown("m1") as Translation.Failed).failure.reason)
        fail = false
        assertTrue(translations.toggle("m1"))
        assertEquals(Translation.Loading, translations.shown("m1"))
        translations.load("m1", "Hola")
        assertTrue(translations.shown("m1") is Translation.Shown)
        assertEquals(2, asked.size)
    }

    @Test fun eachMessageHasItsOwn() = runBlocking {
        translations.toggle("m1")
        translations.load("m1", "Hola")
        assertNull(translations.shown("m2"))
        translations.clear()
        assertNull(translations.shown("m1"))
    }
}
