package com.evanaronson.linguize.ui.notes

/** The parts of the Notes tab, top to bottom (see docs/lab/ia-notes.md). */
internal enum class NotesPart {
    /** Add an API key, until there is one. */
    AddKey,

    /** Write and check (New note at its end), the editor, then Check, Copy and Share. */
    Editor,

    /** The check card, under the editor and its actions. */
    CheckCard,

    /** The other notes, with Undo after a delete. */
    OtherNotes,

    /** Where notes are kept, and the selection-menu tip. */
    Footer,
}

/**
 * What the Notes tab shows, in order. [checking]: a check is open; [hasOthers]: there are
 * other notes, or one was just deleted (its Undo shows there).
 */
internal fun notesParts(needsKey: Boolean, checking: Boolean, hasOthers: Boolean): List<NotesPart> = buildList {
    if (needsKey) add(NotesPart.AddKey)
    add(NotesPart.Editor)
    if (checking) add(NotesPart.CheckCard)
    if (hasOthers) add(NotesPart.OtherNotes)
    add(NotesPart.Footer)
}
