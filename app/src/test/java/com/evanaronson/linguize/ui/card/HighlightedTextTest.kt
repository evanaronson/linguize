package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invisible replacements get controls without borrowing neighboring text into the edit. */
class HighlightedTextTest {
    @Test
    fun deletingAWordKeepsItsOwnSpanAndGainsAVisibleControl() {
        val edit = Edit(0, EditKind.Fix, 5, 8, "yo ", "", null)
        val revision = Revision("Ayer yo fui", listOf(edit))
        val preview = revision.preview(EditKind.Fix)
        val highlight = Highlight(edit.id, preview.ranges.getValue(edit.id), edit.from, edit.why)

        assertEquals("Ayer fui", preview.text)
        assertEquals(5 until 5, highlight.range)
        assertTrue(highlight.needsPanel(preview.text))
        assertEquals("Ayer fui", highlight.context(preview.text))
        assertEquals("Ayer fui", revision.accept(edit.id).workingText)
        assertEquals("Ayer yo fui", revision.accept(edit.id).undo().workingText)
    }

    @Test
    fun lineBreakToSpaceHasAControlAndNearbyWords() {
        val edit = Edit(0, EditKind.Fix, 4, 6, "\r\n", " ", null)
        val revision = Revision("Hola\r\nbon dia", listOf(edit))
        val preview = revision.preview(EditKind.Fix)
        val highlight = Highlight(edit.id, preview.ranges.getValue(edit.id), edit.from, edit.why)

        assertTrue(highlight.needsPanel(preview.text))
        assertEquals("Hola bon", highlight.context(preview.text))
        assertEquals("Hola bon dia", revision.accept(edit.id).workingText)
    }

    @Test
    fun unicodeLineBreaksAlsoGetVisibleControls() {
        for (lineBreak in listOf("\r\n", "\r", "\n", "\u0085", "\u2028", "\u2029")) {
            val text = "Bon${lineBreak}dia"
            val highlight = Highlight(0, 3 until 3 + lineBreak.length, " ", null)
            assertTrue(lineBreak, highlight.needsPanel(text))
        }
    }

    @Test
    fun displayedContextKeepsWholeEmojiAndCombiningSequences() {
        val text = "👍🏽 cafe\u0301"
        val range = text.indexOf(' ') until text.indexOf(' ') + 1
        val highlight = Highlight(0, range, "\n", null)
        assertEquals(text, highlight.context(text))
    }

    @Test
    fun ordinaryWordChangesKeepTheirExistingTextHighlight() {
        assertFalse(Highlight(0, 4 until 9, "estas", null).needsPanel("Com estàs?"))
    }

    @Test
    fun deletionsAtTheTextEdgesHaveContextWithoutAnOutOfBoundsRange() {
        val text = "Bon dia"
        val beginning = Highlight(0, 0 until 0, "Eh, ", null)
        val end = Highlight(1, text.length until text.length, "!", null)
        assertTrue(beginning.needsPanel(text))
        assertTrue(end.needsPanel(text))
        assertEquals("Bon", beginning.context(text))
        assertEquals("dia", end.context(text))
        assertEquals("", Highlight(2, 0 until 0, "x", null).context(""))
    }
}
