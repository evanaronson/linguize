package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases in turning the model's texts into edits, each one a bug that was found and fixed. */
class EditsTest {
    private fun revision(original: String, corrected: String = "", natural: String = "", naturalChanges: List<VerdictChange> = emptyList()) =
        (
            interpret(
                original,
                Verdict(
                    status = Verdict.Status.Ok,
                    hasErrors = corrected.isNotEmpty(),
                    corrected = corrected,
                    moreNatural = natural.isNotEmpty(),
                    natural = natural,
                    naturalChanges = naturalChanges,
                ),
            ) as CheckResult.Reviewed
            ).revision

    private fun Revision.changes(kind: EditKind) = remaining(kind).map { it.from to it.replacement }

    @Test
    fun aWordAndACommaInsertedTogetherKeepTheirOrder() {
        val revision = revision("hola cómo estás", corrected = "hola amor, cómo estás")
        assertEquals("hola amor, cómo estás", revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun hyphensAndMiddleDotsBelongToTheWord() {
        assertEquals(listOf("dirho" to "dir-ho"), revision("vull dirho ara", corrected = "vull dir-ho ara").changes(EditKind.Fix))
        assertEquals(listOf("colegi" to "col·legi"), revision("al colegi", corrected = "al col·legi").changes(EditKind.Fix))
    }

    @Test
    fun anEmojiIsOneCharacter() {
        assertEquals(listOf("😀" to "😂"), revision("hola 😀", corrected = "hola 😂").changes(EditKind.Fix))
    }

    @Test
    fun aRewordingInsertedBeforeAnInsertedFixGoesInFrontOfIt() {
        val revision = revision("que tal", corrected = "¿Qué tal?", natural = "Hola, ¿qué tal?")
        val expected = "Hola, ¿qué tal?"
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aRewordingInsertedAfterAnInsertedFixKeepsTheFix() {
        val revision = revision("gracias por todo", corrected = "Gracias por todo.", natural = "Gracias por todo. Un beso.")
        val expected = "Gracias por todo. Un beso."
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun changesToSpacingAloneAreNotSuggested() {
        assertEquals(listOf("" to "?"), revision("hola  que tal", corrected = "hola que tal?").changes(EditKind.Fix))
        assertTrue(revision("hola\n\nque tal", corrected = "hola\nque tal").edits(EditKind.Fix).isEmpty())
    }

    @Test
    fun aDeletedWordIsShownWithItsNeighbour() {
        val fixes = revision("hola de de amigos", corrected = "hola  de amigos").remaining(EditKind.Fix)
        assertTrue(fixes.isNotEmpty())
        assertTrue(fixes.all { it.replacement.isNotEmpty() })
    }

    @Test
    fun aReportedChangeThatChangesNothingIsIgnored() {
        val revision = revision(
            "Voy a tomar una ducha y te llamo despues",
            corrected = "Voy a tomar una ducha y te llamo después",
            natural = "Me voy a duchar y te llamo después",
            naturalChanges = listOf(VerdictChange("Voy a tomar una ducha", "Me voy a duchar"), VerdictChange("después", "después")),
        )
        assertEquals(listOf("Voy a tomar una ducha" to "Me voy a duchar"), revision.changes(EditKind.Natural))
    }

    @Test
    fun aFixReplacedByARewordingIsntCountedAsApplied() {
        val revision = revision("Com estas amb la pluja?", corrected = "Com estàs amb la pluja?", natural = "Com portes la pluja?")
        val both = revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural)
        assertEquals("Com portes la pluja?", both.workingText)
        assertEquals(1, both.acceptedCount)
    }

    /** Accepting everything gives [expected] in either order of kinds, and one edit at a time in any order. */
    private fun assertAcceptingAllGives(expected: String, revision: Revision) {
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
        val ids = revision.edits.map { it.id }
        for (order in listOf(ids, ids.reversed())) {
            assertEquals(expected, order.fold(revision) { r, id -> r.accept(id) }.workingText)
        }
    }

    /** No two rewordings overlap or include the same fix. */
    private fun assertRewordingsApart(revision: Revision) {
        val naturals = revision.edits(EditKind.Natural)
        for (x in naturals) for (y in naturals) {
            if (x.id < y.id) {
                assertTrue("$x overlaps $y", !x.overlaps(y))
                assertTrue("$x and $y share a fix", (x.includes intersect y.includes).isEmpty())
            }
        }
    }

    @Test
    fun insertionsAtOnePlaceKeepTheNaturalTextsOrderWhateverTheirKind() {
        // A rewording inserted between two inserted fixes, past a space.
        assertAcceptingAllGives(
            "dijo bajito: «hola»",
            revision("dijo hola", corrected = "dijo: «hola»", natural = "dijo bajito: «hola»"),
        )
        // A rewording inserted before several inserted fixes.
        assertAcceptingAllGives("Oye, ¡¿Vale", revision("vale", corrected = "¡¿Vale", natural = "Oye, ¡¿Vale"))
        assertAcceptingAllGives("?,:x", revision("x", corrected = ",:x", natural = "?,:x"))
    }

    @Test
    fun rewordingsNeverIncludeTheSameFix() {
        val inserted = revision("Oye", corrected = "bien Oye", natural = "sí bien? Oye")
        assertRewordingsApart(inserted)
        assertAcceptingAllGives("sí bien? Oye", inserted)

        val replaced = revision("!", corrected = "E !", natural = "q E ")
        assertRewordingsApart(replaced)
        assertAcceptingAllGives("q E", replaced)
    }

    @Test
    fun anInsertionMovedPastASpaceDoesntLandInsideAFix() {
        val revision = revision("! hola", corrected = "!", natural = "! adiós")
        assertAcceptingAllGives("! adiós", revision)
        val fix = revision.edits(EditKind.Fix).single()
        assertTrue(revision.edits(EditKind.Natural).none { it.overlaps(fix) })
    }

    @Test
    fun aWordWrittenWithASeparateAccentMarkIsTheSameWord() {
        // "café" with "e" + U+0301, as text pasted from macOS often has it, against the composed "é".
        val decomposed = "cafe\u0301 bo"
        assertTrue(revision(decomposed, corrected = "café bo", natural = "café bo").edits.isEmpty())

        val revision = revision(decomposed, corrected = "café bueno", natural = "Un café bueno")
        assertEquals(listOf("bo" to "bueno"), revision.changes(EditKind.Fix))
        assertEquals(decomposed.indexOf("bo"), revision.edits(EditKind.Fix).single().start)
        assertEquals(listOf("" to "Un "), revision.changes(EditKind.Natural))
        assertEquals("Un cafe\u0301 bueno", revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aLineBreakIsNeverSplit() {
        val revision = revision("a\r\nb", corrected = "a\r\nx\r\nb")
        for (edit in revision.edits) {
            for (at in listOf(edit.start, edit.end)) assertFalse("$edit", "a\r\nb".getOrNull(at - 1) == '\r' && "a\r\nb".getOrNull(at) == '\n')
        }
        assertEquals("a\r\nx\r\nb", revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun aReportedRewordingNeverSplitsACharacter() {
        val original = "hola 👨‍👩‍👧 adiós"
        val revision = revision(original, natural = "hola 👨‍👩‍👦 adiós", naturalChanges = listOf(VerdictChange("👧", "👦")))
        assertEquals(listOf("👨‍👩‍👧" to "👨‍👩‍👦"), revision.changes(EditKind.Natural))

        // A lone half of an emoji's surrogate pair.
        val split = revision("😀 hola", natural = "X😀 hola", naturalChanges = listOf(VerdictChange("\uD83D", "X\uD83D")))
        for (edit in split.edits) {
            for (at in listOf(edit.start, edit.end)) assertFalse("$edit", "😀 hola".getOrNull(at)?.isLowSurrogate() == true)
        }
        assertEquals("X😀 hola", split.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun noSuggestionAddsALink() {
        val original = "escribeme mañana"
        val fixes = revision(original, corrected = "Escríbeme mañana en evil.com").changes(EditKind.Fix)
        assertEquals(listOf("escribeme" to "Escríbeme"), fixes)
        assertEquals(emptyList<Any>(), revision(original, corrected = "escribeme mañana https://x.io/a").edits)
        assertEquals(emptyList<Any>(), revision(original, natural = "Escríbeme a www.ejemplo.net mañana").edits)
        // Pieces of an address inserted as separate fixes.
        assertEquals(emptyList<Any>(), revision("hola", corrected = "hola evil.com").edits)
        assertEquals(emptyList<Any>(), revision("hola evil com", corrected = "hola evil.com").edits)
        // Pieces that add a link only when some of them are accepted: "evil.com" without the "!".
        assertEquals(emptyList<Any>(), revision("hola evil 1", corrected = "hola evi!.com").edits)

        // A link already in the text, and abbreviations, are fine.
        assertEquals(
            listOf("mira" to "Mira", "" to "."),
            revision("mira https://ejemplo.com", corrected = "Mira https://ejemplo.com.").changes(EditKind.Fix),
        )
        assertEquals("vivo en EE.UU.", revision("vivo en EE UU", corrected = "vivo en EE.UU.").preview(EditKind.Fix).text)
        // Words a missing space joins look like an address, but fixing their accents adds none.
        assertEquals("estás. Bien", revision("estas.Bien", corrected = "estás. Bien").preview(EditKind.Fix).text)
    }
}
