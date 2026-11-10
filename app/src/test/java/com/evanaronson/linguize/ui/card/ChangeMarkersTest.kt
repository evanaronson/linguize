package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, Review flow: deletions and spacing are drawn at their place in the text. */
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
        assertEquals("cinema", marked.text.substring(marked.ranges.getValue(1)))
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

    @Test fun ordinaryChangesNeedNoMarks() {
        val marked = markChanges("Com estàs?", listOf(Highlight(0, 4 until 9, "estas", null)))
        assertEquals("Com estàs?", marked.text)
        assertEquals(4 until 9, marked.ranges[0])
        assertFalse(marked.hasMarks)
    }
}
