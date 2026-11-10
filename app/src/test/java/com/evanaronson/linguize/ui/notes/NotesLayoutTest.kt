package com.evanaronson.linguize.ui.notes

import com.evanaronson.linguize.ui.notes.NotesPart.AddKey
import com.evanaronson.linguize.ui.notes.NotesPart.CheckCard
import com.evanaronson.linguize.ui.notes.NotesPart.Editor
import com.evanaronson.linguize.ui.notes.NotesPart.Footer
import com.evanaronson.linguize.ui.notes.NotesPart.OtherNotes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Notes tab's order (docs/lab/ia-notes.md). */
class NotesLayoutTest {
    @Test fun theEditorComesFirstAndTheCaptionsLast() {
        assertEquals(listOf(Editor, Footer), notesParts(needsKey = false, checking = false, hasOthers = false))
    }

    @Test fun theAddKeyStripLeadsUntilThereIsAKey() {
        assertEquals(listOf(AddKey, Editor, Footer), notesParts(needsKey = true, checking = false, hasOthers = false))
    }

    @Test fun theCardSitsRightUnderTheEditorAboveTheOtherNotes() {
        assertEquals(
            listOf(AddKey, Editor, CheckCard, OtherNotes, Footer),
            notesParts(needsKey = true, checking = true, hasOthers = true),
        )
        assertEquals(listOf(Editor, OtherNotes, Footer), notesParts(needsKey = false, checking = false, hasOthers = true))
    }
}
