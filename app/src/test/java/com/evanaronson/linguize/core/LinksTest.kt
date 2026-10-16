package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun aSafeBoundaryRepairSurvivesBesideAnAmbiguousDomainChange() {
        val original = "alsofà.Si"
        val wordSpace = Edit(0, EditKind.Fix, 0, 6, "alsofà", "al sofà", null)
        val boundarySpace = Edit(1, EditKind.Fix, 7, 7, "", " ", null)
        assertEquals(listOf(boundarySpace), Links.withoutNew(original, listOf(wordSpace, boundarySpace)))
        assertEquals("alsofà. Si", render(original, listOf(boundarySpace)).text)
        // Once the boundary is real, the independent word-spacing correction is safe.
        assertEquals(listOf(wordSpace), Links.withoutNew("alsofà. Si", listOf(wordSpace)))
    }

    @Test
    fun separateSafeEditsCannotCombineIntoANewAddress() {
        val original = "evil com"
        val removeSpace = Edit(0, EditKind.Fix, 4, 5, " ", "", null)
        val insertDot = Edit(1, EditKind.Fix, 4, 4, "", ".", null)
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, listOf(removeSpace, insertDot)))
    }

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
    fun onlyTheSupportedUppercaseAbbreviationsAreExempt() {
        assertTrue(Links.hasNewLink("AT TO", "AT.TO"))
        assertTrue(Links.hasNewLink("AT TO", "AT.TO/path"))
        assertFalse(Links.hasNewLink("AT.TO", "at.to"))
        assertFalse(Links.hasNewLink("EE UU y RR HH", "EE.UU. y RR.HH."))
        assertTrue(Links.hasNewLink("EE UU", "EE.UU/path"))
    }

    @Test
    fun bareIpv4AddressesKeepTheirCompleteDestinationIdentity() {
        for (address in listOf("1.1.1.1", "0.0.0.0", "255.255.255.255", "192.168.0.1:8080/path?code=AbC#top",
            "https://1.1.1.1", "www.1.1.1.1")) {
            assertTrue(address, Links.hasNewLink("", "Consulta ($address)."))
            assertFalse(address, Links.hasNewLink("Consulta $address", "Consulta ($address)."))
        }
        assertTrue(Links.hasNewLink("1.1.1.1", "1.1.1.2"))
        assertTrue(Links.hasNewLink("1.1.1.1/Account", "1.1.1.1/account"))
        assertTrue(Links.hasNewLink("1.1.1.1?code=AbC", "1.1.1.1?code=abc"))
        assertTrue(Links.hasNewLink("1.1.1.1#Top", "1.1.1.1#top"))
    }

    @Test
    fun invalidOctetsAndNumericProseDoNotBecomePartialIpv4Addresses() {
        for (text in listOf(
            "256.1.1.1", "1.256.1.1", "1.1.256.1", "1.1.1.256", "01.1.1.1",
            "1.2.3", "1.2.3.4.5", "v1.2.3.4", "1.2.3.4beta", "build_1.2.3.4", "1.2.3.4_5",
            "１.１.１.１",
        )) {
            assertFalse(text, Links.hasNewLink("", text))
        }
    }

    @Test
    fun splitSeparatorEditsCannotAssembleANewUppercaseHostOrIpv4Address() {
        for ((original, expected) in listOf(
            "hola AT TO adios" to listOf("Hola AT TO adios", "hola AT TO adiós", "Hola AT TO adiós"),
            "hola 1 1 1 1 adios" to listOf("Hola 1 1 1 1 adios", "hola 1 1 1 1 adiós", "Hola 1 1 1 1 adiós"),
        )) {
            val greeting = Edit(0, EditKind.Fix, 0, 4, "hola", "Hola", null)
            val farewellStart = original.lastIndexOf("adios")
            val farewell = Edit(100, EditKind.Fix, farewellStart, original.length, "adios", "adiós", null)
            val separators = original.indices.filter { it > 4 && it < farewellStart - 1 && original[it] == ' ' }
                .flatMapIndexed { index, position ->
                    listOf(
                        Edit(2 * index + 1, EditKind.Fix, position, position + 1, " ", "", null),
                        Edit(2 * index + 2, EditKind.Fix, position, position, "", ".", null),
                    )
                }
            val safe = Links.withoutNew(original, listOf(greeting) + separators + farewell)
            assertEquals(listOf(greeting, farewell), safe)
            val revision = Revision(original, safe)
            for (mask in 1 until (1 shl safe.size)) {
                val accepted = safe.filterIndexed { index, _ -> mask shr index and 1 == 1 }
                    .fold(revision) { current, edit -> current.accept(edit.id) }
                assertEquals(expected[mask - 1], accepted.workingText)
            }
        }
    }

    @Test
    fun editsCannotTurnAnExistingAddressIntoADifferentSubstringAddress() {
        val original = "Consulta notevil.com"
        val edits = Edits.fixes(original, "Consulta evil.com",
            listOf(VerdictChange("notevil.com", "evil.com"))).edits
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
    fun manySmallRunsCannotExhaustAggregateScanningWork() {
        val original = List(333) { ";".repeat(8) }.joinToString(" ")
        val edits = original.indices.filter { original[it] == ';' }.mapIndexed { id, position ->
            Edit(id, EditKind.Fix, position, position + 1, ";", ":", null)
        }
        assertEquals(2_664, edits.size)
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits))
    }

    @Test
    fun evenOneEightEditRunBesideALongTokenIsWithheld() {
        val original = ";".repeat(8) + " " + ";".repeat(8) + " " + "x".repeat(2_978)
        val edits = original.indices.filter { original[it] == ';' }.mapIndexed { id, position ->
            Edit(id, EditKind.Fix, position, position + 1, ";", ":", null)
        }
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits.take(8)))
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits))
    }

    @Test
    fun repeatedScansOfALongFailedTokenExhaustWorkBeforeTheCharacterAllowance() {
        val original = ";".repeat(8) + " " + "x".repeat(1_000)
        val edits = (0 until 8).map { position ->
            Edit(position, EditKind.Fix, position, position + 1, ";", ":", null)
        }
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits))
    }

    @Test
    fun replacementExpansionCountsTowardTheAggregateLimit() {
        val original = ";".repeat(8)
        fun edits(length: Int) = original.indices.map { position ->
            Edit(position, EditKind.Fix, position, position + 1, ";", ": ".repeat(length), null)
        }
        assertEquals(edits(100), Links.withoutNew(original, edits(100)))
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, edits(250)))
    }

    @Test
    fun manyIndependentCorrectionsInOrdinaryProseRemainAvailable() {
        val original = List(100) { "hola; mundo" }.joinToString(" ")
        val edits = original.indices.filter { original[it] == ';' }.mapIndexed { id, position ->
            Edit(id, EditKind.Fix, position, position + 1, ";", ":", null)
        }
        assertEquals(edits, Links.withoutNew(original, edits))
    }

    @Test
    fun deletedSpacesAndLongReplacementsCountTowardTokenWidth() {
        val original = List(8) { "x".repeat(300) }.joinToString(" ")
        val joins = original.indices.filter { original[it] == ' ' }.mapIndexed { id, position ->
            Edit(id, EditKind.Fix, position, position + 1, " ", "", null)
        }
        assertEquals(emptyList<Edit>(), Links.withoutNew(original, joins))
        val replacement = Edit(0, EditKind.Fix, 0, 1, "a", "x".repeat(2_000), null)
        assertEquals(emptyList<Edit>(), Links.withoutNew("a", listOf(replacement)))
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
        assertTrue(Links.hasNewLink("example.com?code=AbC", "example.com?code=abc"))
        assertTrue(Links.hasNewLink("example.com#Account", "example.com#account"))
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
