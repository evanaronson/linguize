package com.evanaronson.linguize.core.writingpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {
    /** Applying [hunks] of [old] must give [new]: the hunks account for every change. */
    private fun replay(old: String, new: String, hunks: List<Hunk>): String {
        val out = StringBuilder()
        var at = 0
        for (hunk in hunks) {
            out.append(old, at, hunk.oldStart)
            out.append(new, hunk.newStart, hunk.newEnd)
            at = hunk.oldEnd
        }
        out.append(old, at, old.length)
        return out.toString()
    }

    private fun check(old: String, new: String): List<Hunk> {
        val hunks = TextDiff.hunks(old, new)
        assertEquals(new, replay(old, new, hunks))
        // In order and not overlapping, with consistent positions on both sides.
        hunks.zipWithNext().forEach { (a, b) -> assertTrue(a.oldEnd <= b.oldStart && a.newEnd <= b.newStart) }
        return hunks
    }

    @Test fun sameTextHasNoHunks() {
        assertEquals(emptyList<Hunk>(), TextDiff.hunks("Bon dia", "Bon dia"))
    }

    @Test fun typingIsOneInsertion() {
        assertEquals(listOf(Hunk(7, 7, 7, 8)), check("Bon dia", "Bon dia!"))
        assertEquals(listOf(Hunk(5, 5, 5, 6)), check("Bon dia", "Bon ddia"))
        assertEquals(listOf(Hunk(0, 0, 0, 2)), check("dia", "a dia"))
    }

    @Test fun backspaceIsOneDeletion() {
        assertEquals(listOf(Hunk(6, 7, 6, 6)), check("Bon dia", "Bon di"))
    }

    @Test fun repeatedLettersStillGiveAValidHunk() {
        // "aa" -> "aaa" can be told apart only by position; any valid answer replays.
        val hunks = check("baab", "baaab")
        assertEquals(1, hunks.single().delta)
    }

    @Test fun doesNotSplitSurrogatePairs() {
        val old = "Hola 😀 amic"
        val new = "Hola 😁 amic"
        val hunk = check(old, new).single()
        assertEquals(5, hunk.oldStart)
        assertEquals(7, hunk.oldEnd)
    }

    @Test fun doesNotSplitACombiningAccentFromItsLetter() {
        // "e" + combining acute: the accent belongs to the "e", so the change starts at the "e".
        val hunk = check("café amb llet", "cafe amb llet").single()
        assertEquals(3, hunk.oldStart)
    }

    @Test fun separateChangesInOnePasteKeepTheWordsBetweenThemUnchanged() {
        val old = "Hola amic. Avui fa sol i anem a la platja. Fins aviat."
        val new = "Hola amics. Avui fa sol i anem a la platja. Fins demà."
        val hunks = check(old, new)
        assertEquals(2, hunks.size)
        // Nothing between the two changes counts as changed.
        assertTrue(hunks[0].oldEnd <= old.indexOf("Avui"))
        assertTrue(hunks[1].oldStart >= old.indexOf("Fins"))
    }

    @Test fun longRewritesFallBackToOneHunk() {
        val old = "a".repeat(800) + " x " + "b".repeat(800)
        val new = "c".repeat(800) + " x " + "d".repeat(800)
        assertEquals(1, check(old, new).size)
    }
}
