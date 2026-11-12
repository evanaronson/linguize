package com.evanaronson.linguize.ui.notes

import com.evanaronson.linguize.ui.notes.NotePart.AddKey
import com.evanaronson.linguize.ui.notes.NotePart.CheckCard
import com.evanaronson.linguize.ui.notes.NotePart.Editor
import com.evanaronson.linguize.ui.notes.NotePart.Footer
import com.evanaronson.linguize.ui.notes.NotesListPart.Empty
import com.evanaronson.linguize.ui.notes.NotesListPart.Notes
import com.evanaronson.linguize.ui.notes.NotesListPart.Undo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The order of a note's page and of the Notes list (docs/lab/ia-notes.md). */
class NotesLayoutTest {
    @Test fun theEditorComesFirstAndTheCaptionsLast() {
        assertEquals(listOf(Editor, Footer), noteParts(needsKey = false, checking = false))
    }

    @Test fun theAddKeyStripLeadsUntilThereIsAKey() {
        assertEquals(listOf(AddKey, Editor, Footer), noteParts(needsKey = true, checking = false))
    }

    @Test fun theCardSitsRightUnderTheEditor() {
        assertEquals(listOf(AddKey, Editor, CheckCard, Footer), noteParts(needsKey = true, checking = true))
        assertEquals(listOf(Editor, CheckCard, Footer), noteParts(needsKey = false, checking = true))
    }

    @Test fun theListShowsTheNotesWithUndoFirstAfterADelete() {
        assertEquals(listOf(Notes), notesListParts(listed = true, hasNotes = true, justDeleted = false))
        assertEquals(listOf(Undo, Notes), notesListParts(listed = true, hasNotes = true, justDeleted = true))
        assertEquals(listOf(Undo), notesListParts(listed = true, hasNotes = false, justDeleted = true))
    }

    @Test fun theEmptyListSaysWhatItsForOnceTheNotesAreRead() {
        assertEquals(listOf(Empty), notesListParts(listed = true, hasNotes = false, justDeleted = false))
        assertEquals(emptyList<NotesListPart>(), notesListParts(listed = false, hasNotes = false, justDeleted = false))
    }
}
