package com.evanaronson.linguize.ui.notes

/** The parts of a note's page, top to bottom (see docs/lab/ia-notes.md). */
internal enum class NotePart {
    /** Add an API key, until there is one. */
    AddKey,

    /** Write and check, the editor, then Check, Copy and Share. */
    Editor,

    /** The check card, under the editor and its actions. */
    CheckCard,

    /** Where notes are kept, and the selection-menu tip. */
    Footer,
}

/** What a note's page shows, in order. [checking]: a check is open. */
internal fun noteParts(needsKey: Boolean, checking: Boolean): List<NotePart> = buildList {
    if (needsKey) add(NotePart.AddKey)
    add(NotePart.Editor)
    if (checking) add(NotePart.CheckCard)
    add(NotePart.Footer)
}

/** The parts of the Notes tab (the list), top to bottom; New note floats over them. */
internal enum class NotesListPart {
    /** "Note deleted" with Undo, after a delete. */
    Undo,

    /** The notes, newest first. */
    Notes,

    /** There are no notes yet: what the tab is for. */
    Empty,
}

/**
 * What the Notes tab shows, in order. [listed]: the notes have been read; [hasNotes]: there
 * are some; [justDeleted]: one was just deleted (its Undo shows first).
 */
internal fun notesListParts(listed: Boolean, hasNotes: Boolean, justDeleted: Boolean): List<NotesListPart> = buildList {
    if (justDeleted) add(NotesListPart.Undo)
    if (hasNotes) add(NotesListPart.Notes) else if (listed && !justDeleted) add(NotesListPart.Empty)
}
