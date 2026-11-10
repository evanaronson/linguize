# Notes

**Notes** is the tab where you write a message, check it and take it elsewhere. It was
called **Write**; it was renamed because the tab is a place (the notes you keep), not an
instruction, and the other tabs are places too (Chat, Progress, History). What it holds are
**notes**: the editor's drafts, kept on the phone.

## The rename

| Before | After |
|---|---|
| Tab and bottom bar: Write | Notes |
| Launcher shortcut: Write / "Write a message" | Notes / "Write a note" (same id, so a pinned one keeps working) |
| New draft, Drafts, Open draft, Delete draft, Draft deleted | New note, the Notes list (was "Other notes"), Open note, Delete note, Note deleted |
| The editor's language picker (Auto Detect, Catalanize, Castilianize) | Gone: Notes checks in the app's language, set by the one selector at the top (Linguize detects). See [Language scope](language-scope.md) |
| Settings, Chat: "Off starts on Write" | "Off starts on Notes" |
| Saved navigation token `home` | `notes`; `home` (and the removed pages that sat above it) still restore as Notes |

The editor's header stays "Write and check": it says what to do in the box, not the tab's
name, which the bottom bar already shows. Code: `ui/notes` (`NotesViewModel`, `NoteEditor`,
`NoteList`, `noteCheckLanguage`), `ui/home/NotesScreen.kt` (`NotesScreen`, `NotePage`),
`Screen.Notes`, `Screen.Note`. The store keeps its name and file
(`data/write/WriteDraftStore`, `lab_write.json`), so no note moves.

Storage failures keep the current text or failed Undo available for Retry, on both
the list and editor. A deletion changes the list and offers Undo only after its
write succeeds; saves captured before that deletion cannot recreate the note.
Opening another note preserves the outgoing input if saving or opening fails.
Transient read failures remain failures, and legacy-pad import is acknowledged
only after the imported notes are saved.

## A list of notes, each on its own page

The tab used to be one editor with the open note inline at the top, **New note** in its
header (which swapped the editor to a blank note) and **Other notes** listed below. It mixed
two models: a single scratchpad, and a collection. New note felt like drilling down (a fresh
page) but nothing let you drill back: the previous note just moved into a list under the
new one, and Back left the tab.

### The question: one scratchpad, or a collection?

| | Single scratchpad with a drafts switcher | Collection: list, then a page per note |
|---|---|---|
| Familiar from | Simple editors, a "recent drafts" menu | Google Keep, Apple Notes, Samsung Notes; and this app's own Chat |
| New note | Swaps the content in place; the old one goes somewhere | Opens a new page; Back returns to the list, where the new note now is |
| Coming back to an earlier note | Find the switcher | The tab's root is the list |
| Back | Leaves the tab, whatever note was swapped in | Note → list → start |
| The tab's root | Changes with whatever note is open | Always the same: the list |
| Keyboard and the check card | The list sits under the card, scrolled past while writing | The page is only this note: editor, actions, card |

What people do here (see [the hierarchy](ia-notes.md)): write and check one message
quickly, and come back to an earlier one. Both are served by a collection, as long as
writing is still one tap away. The scratchpad only wins on "type at once", and that's kept
(below).

**Consistency with Chat decides it.** Chat is a list of conversations with **New
conversation** as the main button at the bottom; a conversation opens above the list with
Back to it, and the app opens straight into the current one. Notes now works the same way,
so the two tabs that hold things you write read as one pattern.

### Decision

- **Notes (the tab) is the list**: every note, newest first (first line or title, date,
  delete with Undo), and **New note** as the main button at the bottom, like New
  conversation. With no notes: "No notes yet" and what the tab is for. The root never
  changes, so the Notes tab button and Back always lead to the same place.
- **A note opens on its own page** above the list (`Screen.Note(id)`): Back and the title
  "Note"; the add-key strip until there is a key; Write and check, the editor, Check, Copy,
  Share; the card below; the two captions. Everything on it acts on this note.
- **New note** opens a new, blank note's page with the cursor in it, so the keyboard comes up
  at once. Nothing is kept until something is written: Back from a blank note leaves no empty
  row. Back closes the keyboard first, then returns to the list, where the note now is.
- **Start writing at once.** With Notes as the start tab (Start on), the app opens straight
  into the current note (the last one opened, else the newest), with the list one Back
  away, as Chat opens into the current conversation. On a fresh install that's the sample
  note. The Notes launcher shortcut ("Write a note") does the same above whichever tab the
  app starts on. The Notes tab button itself always opens the list.
- **The language selector stays on a note's page**, at the right of its bar (no gear). The
  note's checks follow the app's language, and the card's "Choose Linguize at the top" hint
  must stay true. Pages Progress opens don't show it, because switching would change the page
  under the reader; a note doesn't change, only the next check does. A conversation doesn't,
  because its language is fixed.
- **One note at a time.** Opening another note replaces the open one, so Back still leads to
  the list. Opening another note closes the card, which was for the other note's text;
  going back to the list and into the same note keeps it.

Not chosen:
- *Keep the inline editor, and move Other notes to a page from the header.* The root would
  still change with the open note, New note would still swap content in place, and it would
  be the only tab whose list is a page below its own content.
- *An editor with a drafts dropdown.* A filter-shaped control for navigation, and Back would
  still not return to the previous note.

What didn't change: saving (as you type, at once on leaving the page or the app), the sample
and its rule (never saved untouched), the Writing pad import, the store and its file, the
check, its card and its language, Copy and Share, and that checks here aren't in History or
timed.

The rest of this page is the decision that made the tab, when it was still Write.

## Background: one surface

Write had two places to type a message and check it: **Try it** at the top of the tab and
the **Writing pad**, a full-screen editor one row below. This page asks whether both are
needed, and records the decision: they are merged into one surface, **Write**, at the top
of the tab, and the Writing pad is gone.

## The jobs

| Job | What it needs | Try it | Writing pad |
|---|---|---|---|
| See how the app works | A sample, one button, the real card | Yes | No (empty editor, live underlines unlike anything else) |
| Check one message, then paste it elsewhere | A box, the card, Copy or Share | Partly: no Copy or Share of the whole text, lost when you leave | Yes, but with a different review UX |
| Write something longer over time (an email to the school) | A roomy editor that never loses text; a way back to it later | No: text lives in screen state | Yes: drafts |

Seen from the person, these are one job: *write a message here, check it, take it
somewhere else*. Trying the app is just the first time you do it. The difference
between Try it and the pad was length and patience, not purpose.

## Overlap with the rest of the app

- **Chat** checks each message with the same card before it goes. Its entry differs only
  because the text arrives by typing and leaves by Send.
- **Selection menu and accessibility button** check text in another app with the same card
  and put it back with Apply.
- **Try it** used the same card. Only the **Writing pad** had a second check experience:
  checks while you pause, underlines in the editor, a panel per change, its own status
  line and its own Meaning strip, but no clarifying, no assumptions, no How it lands, no
  Undo.

## What live checking added, and what it cost

It added: no button to press, and changes shown in your own text. For a learner that has
real costs:

- **A second way to review.** Two visual languages for the same changes, and the pad's was
  missing half of what the card does (assumptions, clarifying, the card rows). Every card
  improvement had to be built twice or not reach the pad.
- **Interruption.** Underlines appearing at every pause pull attention to fixing while
  composing. Writing first, then checking once, matches how the rest of the app works and
  how people write a longer message.
- **Cost and noise.** A full check (the whole draft, up to 3,000 characters) at every
  pause, most of them thrown away by the next edit.
- **Complexity.** About 1,700 lines of engine, editor, view model and screen (scheduler,
  diff, moving suggestions through edits, dismissal memory), plus an activity, for one
  entry point.

Drafts are the pad's lasting value: a longer message is written over time, and losing it
when you switch apps is the worst outcome. Try it had no drafts at all.

## Decision: one Write surface

Merge into a single editor at the top of Write, checked with the one card everyone uses.

- **One check.** Check opens the same card as everywhere (accept a change, Accept all,
  Meaning, clarifying, How it lands (in the same check), filled [gaps]).
  **Apply** puts the accepted changes in the editor. **Copy** and **Share** take the text
  out, with any accepted changes put in first.
- **Nothing lost.** The editor is a draft, saved on the phone as you type and when you
  leave. **New draft** starts a blank one and keeps the last in a short **Drafts** list
  below; tap one to go back to it, or delete it (with Undo). (Since then: the tab is the
  list and each note has its own page; see above.)
- **Trying the app** still works: on a fresh install the editor holds the sample message,
  which isn't saved as a draft until you change it.
- **Writing pad drafts move in.** The first time Write opens, drafts in the pad's
  `lab_writing_pad.json` are copied into Write's own file (titles kept for the list). The
  old file is left as it was.

Not chosen:
- *Keep both with clearer roles.* The roles only differed in length and live checking;
  two surfaces would keep two check experiences.
- *Remove the pad, keep Try it as it was.* Loses drafts, the one thing the pad did that
  matters, and Try it would still lose text when you leave.

## How it works

- `data/write/WriteDraftStore` (the notes; names kept from Write): drafts and which one is open, in `lab_write.json` in the
  app's files directory, written through a temporary file and a rename. An unreadable file
  is set aside, not overwritten. Reads `lab_writing_pad.json` once to import its drafts and
  never writes it.
- `ui/notes/NotesViewModel`: every note (the list) and the one open on a note's page.
  `open(id)` opens a note by id (a new note's id from `newNoteId()`, blank until something is
  written), or with null the current one (the last opened, else the newest, else the
  sample); it returns false when that note is already open, so the card stays. Saves 400 ms
  after typing stops, and at once on leaving the page or the app. A blank note, or the
  untouched sample, is never kept; an emptied note keeps its id.
- Navigation: `Screen.Note(id)` above `Screen.Notes` (`lab_notes_note:<id>`; null is "the
  current note", `lab_notes_note`, replaced by its id once known, as Chat's current
  conversation is). `startNavigation` opens it for the Notes start and the Notes shortcut.
- `ui/notes/NoteEditor`: "Write and check"; the editor with Check, Copy and Share in one row
  under it; the card below the editor (`NoteCheckCard`, `CardActions.of`, as Try it used);
  the captions; and the list's rows (`NoteList`, `NoteDeletedRow`, `NotesEmpty`). The orders
  are `noteParts` and `notesListParts` (see [Notes: information hierarchy](ia-notes.md)). It checks in the app's language
  (`noteCheckLanguage`); the card's wrong-language hint points at the selector at the top
  (`LanguageHint.AppSelector`). Checks use `Origin.Tester` and aren't saved
  in History, as Try it's weren't.

## Limits

- Checks here aren't saved in History. Treating Copy or Share as "sent" would make Write
  a source like Chat; that's a separate decision about History's sources.
- One check at a time, by button. There is no checking as you type.
- The whole draft is checked, up to Linguize's 3,000-character limit.
- Drafts are on this phone only. Removing Linguize Lab deletes them.
