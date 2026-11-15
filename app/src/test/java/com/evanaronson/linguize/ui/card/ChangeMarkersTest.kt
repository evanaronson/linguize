package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One change view: every change is written at its place in the text, old words struck through, then the new. */
class ChangeMarkersTest {
    private fun highlights(revision: Revision) = revision.preview(EditKind.Fix).let { preview ->
        preview.text to revision.edits.map { Highlight(it.id, preview.ranges.getValue(it.id), it.from, it.why) }
    }

    @Test fun aDeletedWordStaysInPlaceStruckThroughWithItsOwnSpaces() {
        val revision = Revision("Ayer yo fui", listOf(Edit(0, EditKind.Fix, 5, 8, "yo ", "", null)))
        val (text, changes) = highlights(revision)
        val marked = markChanges(text, changes)
        assertEquals("Ayer yo fui", marked.text)
        assertEquals(5..6, marked.ranges[0])
        assertEquals(listOf(5..6), marked.struck)
        assertTrue(marked.hasMarks)
    }

    @Test fun aDeletionBeforeAPeriodIsPaddedOnlyWhereNeeded() {
        val revision = Revision("Bon dia jo.", listOf(Edit(0, EditKind.Fix, 7, 10, " jo", "", null)))
        val (text, changes) = highlights(revision)
        val marked = markChanges(text, changes)
        assertEquals("Bon dia jo.", marked.text)
        assertEquals("jo", marked.text.substring(marked.ranges.getValue(0)))
    }

    @Test fun laterChangesMoveAlongAndKeepTheirWords() {
        val revision = Revision("Ayer yo fui al cine", listOf(
            Edit(0, EditKind.Fix, 5, 8, "yo ", "", null),
            Edit(1, EditKind.Fix, 15, 19, "cine", "cinema", null),
        ))
        val (text, changes) = highlights(revision)
        val marked = markChanges(text, changes)
        assertEquals("Ayer yo fui al cine cinema", marked.text)
        assertEquals("cine cinema", marked.text.substring(marked.ranges.getValue(1)))
        assertEquals("cinema", marked.text.substring(marked.added.getValue(1)))
        assertEquals("cine", marked.text.substring(marked.struckBy.getValue(1)))
        assertEquals("yo", marked.text.substring(marked.ranges.getValue(0)))
    }

    @Test fun addedSpacingGetsACaretAndALineBreakAPilcrow() {
        val space = Revision("Hola,bon dia", listOf(Edit(0, EditKind.Fix, 5, 5, "", " ", null)))
        val (spaceText, spaceChanges) = highlights(space)
        val caret = markChanges(spaceText, spaceChanges)
        assertEquals(CARET_MARK, caret.text.substring(caret.ranges.getValue(0)))
        assertTrue(caret.struck.isEmpty())

        val joined = Revision("Hola\r\nbon dia", listOf(Edit(0, EditKind.Fix, 4, 6, "\r\n", " ", null)))
        val (joinedText, joinedChanges) = highlights(joined)
        val pilcrow = markChanges(joinedText, joinedChanges)
        assertEquals(LINE_BREAK_MARK, pilcrow.text.substring(pilcrow.ranges.getValue(0)))
        assertEquals(listOf(pilcrow.ranges.getValue(0)), pilcrow.struck)
    }

    @Test fun deletionsAtBothEdgesAreMarked() {
        val revision = Revision("Eh, bon dia!", listOf(
            Edit(0, EditKind.Fix, 0, 4, "Eh, ", "", null),
            Edit(1, EditKind.Fix, 11, 12, "!", "", null),
        ))
        val (text, changes) = highlights(revision)
        val marked = markChanges(text, changes)
        assertEquals("Eh, bon dia!", marked.text)
        assertEquals("Eh,", marked.text.substring(marked.ranges.getValue(0)))
        assertEquals("!", marked.text.substring(marked.ranges.getValue(1)))
    }

    @Test fun aReplacementShowsItsOldWordsStruckThenTheNewOnesUnderOneMark() {
        val marked = markChanges("Com estàs?", listOf(Highlight(0, 4 until 9, "estas", null)))
        assertEquals("Com estas estàs?", marked.text)
        assertEquals(4..14, marked.ranges[0])
        assertEquals(listOf(4..8), marked.struck)
        assertEquals(10..14, marked.added[0])
        assertTrue(marked.hasMarks)
        // Punctuation next to punctuation sits close; spaces at the edges stay outside the mark.
        val comma = markChanges("Hola, que tal", listOf(Highlight(0, 4..5, "!", null)))
        assertEquals("Hola!, que tal", comma.text)
        assertEquals("!,", comma.text.substring(comma.ranges.getValue(0)))
        val spaced = markChanges("de una casa", listOf(Highlight(0, 2..6, "uno", null)))
        assertEquals("de uno una casa", spaced.text)
        assertEquals("uno una", spaced.text.substring(spaced.ranges.getValue(0)))
    }

    @Test fun insertionsNeedNoMarks() {
        val marked = markChanges("Hola, amic", listOf(Highlight(0, 4..4, "", null)))
        assertEquals("Hola, amic", marked.text)
        assertEquals(4..4, marked.ranges[0])
        assertTrue(marked.struck.isEmpty())
        assertFalse(marked.hasMarks)
    }

    @Test fun aSkippedChangeShowsItsOriginalWordsUnmarked() {
        val revision = Revision("Ayer yo fui al cine con mis amigo", listOf(
            Edit(0, EditKind.Fix, 5, 8, "yo ", "", null),
            Edit(1, EditKind.Fix, 15, 19, "cine", "cinema", null),
            Edit(2, EditKind.Fix, 33, 33, "", "s", null),
        ))
        val (text, changes) = highlights(revision)
        val marked = markChanges(text, changes, skipped = setOf(0, 1, 2))
        // What will be sent for each: the original words, no strike; the skipped insertion keeps a quiet caret.
        assertEquals("Ayer yo fui al cine con mis amigo$CARET_MARK", marked.text)
        assertTrue(marked.struck.isEmpty())
        assertEquals(setOf(0, 1, 2), marked.quiet)
        assertEquals("cine", marked.text.substring(marked.ranges.getValue(1)))
        assertEquals("yo", marked.text.substring(marked.ranges.getValue(0)))
        assertEquals(listOf(marked.ranges.getValue(2)), marked.quietMarks)
        // Only the skipped one changes; the others stay proposed.
        val one = markChanges(text, changes, skipped = setOf(1))
        assertEquals("Ayer yo fui al cine con mis amigos", one.text)
        assertEquals(listOf(one.ranges.getValue(0)), one.struck)
    }
}
