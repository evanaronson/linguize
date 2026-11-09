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
| New draft, Drafts, Open draft, Delete draft, Draft deleted | New note, Other notes, Open note, Delete note, Note deleted |
| The editor's language picker (Auto Detect, Catalanize, Castilianize) | Gone: Notes checks in the app's language, set by the one selector at the top (Linguize detects). See [Language scope](language-scope.md) |
| Settings, Chat: "Off starts on Write" | "Off starts on Notes" |
| Saved navigation token `home` | `notes`; `home` (and the removed pages that sat above it) still restore as Notes |

The editor's header stays "Write and check": it says what to do in the box, not the tab's
name, which the bottom bar already shows. Code: `ui/notes` (`NotesViewModel`, `NoteEditor`,
`NoteList`, `noteCheckLanguage`), `ui/home/NotesScreen.kt`, `Screen.Notes`. The store keeps
its name and file (`data/write/WriteDraftStore`, `lab_write.json`), so no note moves.

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
  Second opinion, no Undo.

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
  Meaning, clarifying, How it lands (in the same check), Second opinion, filled [gaps]).
  **Apply** puts the accepted changes in the editor. **Copy** and **Share** take the text
  out, with any accepted changes put in first.
- **Nothing lost.** The editor is a draft, saved on the phone as you type and when you
  leave. **New draft** starts a blank one and keeps the last in a short **Drafts** list
  below; tap one to go back to it, or delete it (with Undo).
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
- `ui/notes/NotesViewModel`: the open note's text and the other notes.
  Saves 400 ms after typing stops, and at once on leaving the tab or the app. A blank
  draft, or the untouched sample, is never kept.
- `ui/notes/NoteEditor`: the editor, Check, the card (`CardActions.of`, as Try it used),
  Copy, Share, New note and the Other notes list. It checks in the app's language
  (`noteCheckLanguage`); the card's wrong-language hint points at the selector at the top
  (`LanguageHint.AppSelector`). Checks use `Origin.Tester` and aren't saved
  in History, as Try it's weren't.

## Limits

- Checks here aren't saved in History. Treating Copy or Share as "sent" would make Write
  a source like Chat; that's a separate decision about History's sources.
- One check at a time, by button. There is no checking as you type.
- The whole draft is checked, up to Linguize's 3,000-character limit.
- Drafts are on this phone only. Removing Linguize Lab deletes them.
