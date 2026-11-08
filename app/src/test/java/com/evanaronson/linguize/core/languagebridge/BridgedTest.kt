package com.evanaronson.linguize.core.languagebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgedTest {
    @Test fun guessesCatalanAndSpanishFromSurfaceMarkers() {
        assertEquals("ca", LanguageGuess.guess("Bon dia! Avui no puc venir, però demà sí. Gràcies"))
        assertEquals("ca", LanguageGuess.guess("M'agrada molt l'idea, ens veiem a l'escola"))
        assertEquals("es", LanguageGuess.guess("¿Te acuestas tarde? Mañana no puedo, pero gracias"))
        assertEquals("es", LanguageGuess.guess("Perdona que te moleste, estoy muy cansado y quiero dormir"))
    }

    @Test fun staysUndecidedWithoutClearMarkers() {
        assertNull(LanguageGuess.guess(""))
        assertNull(LanguageGuess.guess("Hola"))
        assertNull(LanguageGuess.guess("OK 👍 12:30"))
    }

    @Test fun theDefaultTargetIsTheFirstProfileLanguageThatIsNotTheSource() {
        val profiles = listOf("ca", "es")
        assertEquals("es", BridgeTargets.defaultTarget(profiles, "ca"))
        assertEquals("ca", BridgeTargets.defaultTarget(profiles, "es"))
        assertEquals("ca", BridgeTargets.defaultTarget(profiles, null))
        assertEquals("ca", BridgeTargets.defaultTarget(profiles, "fr"))
        assertNull(BridgeTargets.defaultTarget(listOf("ca"), "ca"))
        assertEquals("ca", BridgeTargets.defaultTarget(listOf("ca"), null))
        assertNull(BridgeTargets.defaultTarget(emptyList(), null))
    }

    @Test fun otherTargetsSkipTheCurrentOneAndTheLanguageTheModelReadTheTextAs() {
        val profiles = listOf("ca", "es", "fr")
        assertEquals(listOf("fr"), BridgeTargets.others(profiles, "ca", " spanish ", BridgeTargets::englishName))
        assertEquals(listOf("es", "fr"), BridgeTargets.others(profiles, "ca", "", BridgeTargets::englishName))
        assertEquals(listOf("es", "fr"), BridgeTargets.others(profiles, "ca", null, BridgeTargets::englishName))
    }

    @Test fun languagesAreNamedInEnglishForThePrompt() {
        assertEquals("Catalan", BridgeTargets.englishName("ca"))
        assertEquals("Spanish", BridgeTargets.englishName("es"))
        assertEquals("French", BridgeTargets.englishName("fr"))
    }

    @Test fun kindsReadTheirSchemaTokens() {
        assertEquals(Correspondence.Kind.FalseFriend, Correspondence.Kind.read("false_friend"))
        assertEquals(Correspondence.Kind.entries, Correspondence.Kind.entries.map { Correspondence.Kind.read(it.token) })
        assertNull(Correspondence.Kind.read("False friend"))
    }
}
