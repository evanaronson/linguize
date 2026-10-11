package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun aSubstringOfAnExistingAddressIsStillANewAddress() {
        assertTrue(Links.hasNewLink("Consulta notevil.com", "Consulta evil.com"))
        assertTrue(Links.hasNewLink("Consulta https://safe.example/?next=evil.com", "Consulta evil.com"))
        assertTrue(Links.hasNewLink("Consulta evil.company", "Consulta evil.com"))
    }

    @Test
    fun completeExistingAddressesAreStillAllowed() {
        assertFalse(Links.hasNewLink("mira https://ejemplo.com", "Mira https://ejemplo.com."))
        assertFalse(Links.hasNewLink("estas.Bien", "estás.Bien"))
        assertFalse(Links.hasNewLink("en EE UU", "en EE.UU."))
    }

    @Test
    fun editsCannotTurnAnExistingAddressIntoADifferentSubstringAddress() {
        val original = "Consulta notevil.com"
        val edits = Edits.fixes(original, "Consulta evil.com", emptyList())
        assertTrue(edits.isNotEmpty())
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits))
    }

    @Test
    fun aLongPhraseAcrossManySpacesIsStillOffered() {
        val original = List(600) { "hola" }.joinToString(" ")
        val edit = Edit(0, EditKind.Natural, 0, original.length, original, "Buenos días", null)
        assertEquals(listOf(edit), Links.withoutNew(original, listOf(edit)))
    }

    @Test
    fun aMalformedAddressCannotExhaustTheRegexStack() {
        assertTrue(Links.hasNewLink("", "a.".repeat(5_000) + "com"))
    }

    @Test
    fun explicitAddressIdentityPreservesPathsQueriesAndUnicodeHosts() {
        assertTrue(Links.hasNewLink("https://example.com/Account", "https://example.com/account"))
        assertTrue(Links.hasNewLink("https://example.com/?code=AbC", "https://example.com/?code=abc"))
        assertTrue(Links.hasNewLink("https://café.com", "https://cafe.com"))
        assertTrue(Links.hasNewLink("www.example.com/Account", "www.example.com/account"))
        assertTrue(Links.hasNewLink("example.com/Account", "example.com/account"))
        assertTrue(Links.hasNewLink("https://User@example.com", "https://user@example.com"))
        assertFalse(Links.hasNewLink("HTTPS://EXAMPLE.COM/Account", "https://example.com/Account"))
    }

    @Test
    fun aHarmlessEditBesideALongLinkDoesNotInventATruncatedDestination() {
        val original = "[hola](https://example.com/" + "a".repeat(1_500) + ")"
        val edit = Edit(0, EditKind.Fix, 1, 5, "hola", "Hola", null)
        assertEquals(listOf(edit), Links.withoutNew(original, listOf(edit)))
    }
}
