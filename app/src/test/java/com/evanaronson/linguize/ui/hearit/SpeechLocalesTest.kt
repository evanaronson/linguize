package com.evanaronson.linguize.ui.hearit

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class SpeechLocalesTest {
    private fun tags(code: String, variety: String?) = SpeechLocales.candidates(code, variety).map { it.toLanguageTag() }

    @Test
    fun `default varieties speak in Spain`() {
        assertEquals(listOf("es-ES", "es"), tags("es", Language.Spanish.variety))
        assertEquals(listOf("ca-ES", "ca"), tags("ca", Language.Catalan.variety))
        assertEquals(listOf("es-ES", "es"), tags("es", null))
        assertEquals(listOf("es-ES", "es"), tags("es", "something nobody wrote before"))
    }

    @Test
    fun `a regional variety picks its region, then nearby voices, then the plain language`() {
        assertEquals(listOf("es-MX", "es-419", "es-US", "es"), tags("es", "Mexican Spanish"))
        assertEquals(listOf("es-AR", "es-419", "es"), tags("es", "Rioplatense (Buenos Aires)"))
        assertEquals(listOf("es-419", "es-US", "es-MX", "es"), tags("es", "Latin American"))
        assertEquals(listOf("es-ES", "es"), tags("es", "Castilian, Madrid"))
        assertEquals(listOf("ca-ES", "ca"), tags("ca", "Valencian"))
        assertEquals(listOf("ca-AD", "ca-ES", "ca"), tags("ca", "Andorran"))
    }

    @Test
    fun `other languages use the plain language`() {
        assertEquals(listOf("fr"), tags("fr", "Parisian"))
    }

    @Test
    fun `meaning uses the interface locale`() {
        assertEquals(listOf("en-GB", "en"), SpeechLocales.forNative(Locale.UK).map { it.toLanguageTag() })
        assertEquals(listOf("en"), SpeechLocales.forNative(Locale.ENGLISH).map { it.toLanguageTag() })
    }

    @Test
    fun `resolve prefers the pinned language and the writer's own variety`() {
        val mexican = WritingProfile.defaultFor(Language.Spanish).copy(variety = "Mexican")
        assertEquals("es-MX", SpeechLocales.resolve(Language.Spanish, "Bon dia, com estàs?", listOf(mexican)).first().toLanguageTag())
        // Auto-detect: the text decides the language.
        assertEquals("ca-ES", SpeechLocales.resolve(null, "Bon dia, com estàs? Vaig a casa amb els nens.", listOf(mexican)).first().toLanguageTag())
        assertEquals("es-MX", SpeechLocales.resolve(null, "¿Mañana vienes con los niños?", listOf(mexican)).first().toLanguageTag())
        // Nothing to go on: Spanish, Peninsular unless the profile says otherwise.
        assertEquals("es-ES", SpeechLocales.resolve(null, "ok", emptyList()).first().toLanguageTag())
    }

    @Test
    fun `guess tells Catalan from Spanish`() {
        assertEquals(Language.Catalan, guessLanguage("Demà no podré venir perquè tinc feina"))
        assertEquals(Language.Catalan, guessLanguage("La col·lecció és molt bonica"))
        assertEquals(Language.Spanish, guessLanguage("Mañana no puedo ir porque tengo trabajo"))
        assertEquals(Language.Spanish, guessLanguage("¿Qué hay para cenar hoy?"))
        assertNull(guessLanguage("ok"))
        assertNull(guessLanguage(""))
    }

    @Test
    fun `dictation replaces the sample or an empty box and follows typed text`() {
        val sample = "Bon dia a tothom"
        assertEquals("Hola", mergeDictation("", "Hola", sample))
        assertEquals("Hola", mergeDictation("  ", " Hola ", sample))
        assertEquals("Hola", mergeDictation(sample, "Hola", sample))
        assertEquals("Ya llego. Voy en metro", mergeDictation("Ya llego. ", "Voy en metro", sample))
        assertEquals("Ya llego.", mergeDictation("Ya llego.", "  ", sample))
    }
}
