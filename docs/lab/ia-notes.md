# Notes: information hierarchy

The Notes tab is where you write a message here, check it and take it elsewhere (see
[Notes](notes.md)). This page looks at what is on the tab, in what order, and why. It
changes placement, grouping and a few captions. It doesn't change what Notes does.

## The person's jobs, in order

1. **Check this message, then take it elsewhere.** Type or paste, Check, accept changes,
   Copy or Share. The reason to open the tab.
2. **Come back to something I was writing.** The open note is there when the tab opens;
   older ones are in Other notes.
3. **Start another message without losing this one.** New note.
4. **First run: see what the app does, and get it working.** The sample message and the
   add-key strip.
5. **Know where my text goes.** Notes stay on the phone; checks here aren't in History.
6. **Tidy up.** Delete a note, with Undo.

## Inventory (before)

Top to bottom, with prominence:

| # | Element | Where | Prominence |
|---|---|---|---|
| 1 | Add-key strip ("Add an API key to start", Add key) | Top, only until there is a key | High: tinted strip |
| 2 | "Write and check" | Section header | Low (muted label) |
| 3 | Editor (4 lines, grows), placeholder "Write or paste a message"; "No text to check." under it after an empty Check | In the editor card | High |
| 4 | First-run sample ("Bon dia! Com estas amb la pluja?") | In the editor, unexplained | Reads as someone else's text |
| 5 | Check | Own row under the editor | Highest: the one filled button |
| 6 | The check card | **Inside** the editor card, under Check | High; framed twice (card in a card), 40dp narrower than other cards |
| 7 | Copy, Share, New note | Text buttons, one row **under the card** | Low; pushed a full card's height away once a check is open |
| 8 | Caption: three sentences (phone storage, History, the selection-menu tip) | Under the buttons, in the editor card | Low, but long |
| 9 | Other notes: header, "Note deleted, Undo", rows (first line, date, delete) | Below the editor card, only when there are others | Medium |

No child pages: a note opens in the editor, and the card's own pages (how the text was
read) are the card's.

## Problems

- **The actions for this text are split by the card.** Check is above it, Copy and Share are
  below it. A long card (several changes, How it lands) puts Copy a scroll away, at the
  moment the person is ready to take the text out.
- **New note sits with Copy and Share,** but it doesn't act on this text: it changes which
  note is open. It's the one button in that row that loses your place.
- **The card is a card inside a card.** It has its own border and corners, so the editor
  card frames it a second time and squeezes it.
- **The caption is three ideas in one block,** in the middle of the working area, below
  every check. Copy style asks for one idea per caption.
- **The sample isn't labelled.** On a fresh install the box holds a Catalan sentence and
  nothing says it's a sample to try.

## Target hierarchy

Primary: the editor and Check. Secondary: the check card, and Copy and Share, which take the
result out. Tertiary: New note, Other notes, the captions.

1. **Add-key strip** (until there is a key). Nothing works without it.
2. **Write and check**, with **New note** at the header's end: the header names the
   note-level area, and the note-level action sits with it.
3. **Editor card**: the editor (with "A sample to try. Check it, or write your own." under
   it while the untouched sample shows), then one action row: **Check** (filled), **Copy**,
   **Share** (text). Everything that acts on this text, together, above the result.
4. **The check card**, directly under the editor card, at full width, no second frame.
5. **Other notes** (when there are others): Undo row, then the list. Unchanged.
6. **Captions**, last: "Notes stay on this phone. Checks here aren't saved in History." and
   "In other apps, select text and choose Linguize in the menu."

The order is `notesParts` in `ui/notes/NotesLayout.kt`, and `NotesScreen` draws it.

## Current vs target

| Element | Current | Target | Why |
|---|---|---|---|
| Add-key strip | Top | Top | Blocks every check |
| Write and check | Header | Header, with New note as its action | Groups the note-level action with the area it changes |
| Editor | Editor card | Editor card | Primary |
| Sample | Unlabelled | Supporting line while it shows: "A sample to try. Check it, or write your own." | Says why there's text already, and that it's not saved |
| Check | Alone on a row | First in one action row | Primary action |
| Copy, Share | Under the card | Beside Check, above the card | Reachable at the moment they're needed |
| New note | Beside Copy and Share | Header action | Not an action on this text |
| Check card | Inside the editor card | Below the editor card, full width | One frame; the result follows its actions |
| Caption | Three sentences in the editor card | Two captions at the end of the tab | Tertiary, one idea each |
| Other notes | Below the editor card | Below the check card | Unchanged; the open note comes first |

### Considered and rejected

- **Check, Copy, Share and New note in one row.** Four buttons wrap at phone width, and it
  keeps the note-level action among the text actions.
- **Other notes as a child page or a dropdown.** There are usually a few; a page is a tap
  more for no gain, and a dropdown would be a filter-shaped control for an action.
- **Hiding Copy and Share while the card is open.** They still work then: they take the
  accepted changes into the note first, as before.
- **A snackbar for "Note deleted".** A new pattern for one row; the inline Undo is already
  where the eye is.
- **Dropping the selection-menu tip.** It's the only pointer in the tab to the app's main
  moment (in other apps). It stays, as its own caption, at the end.
- **Collapsing the card behind a toggle.** The card's expand pattern is decided for the card
  as a whole, not here.

## What didn't change

What Check, Copy, Share, New note, open, delete and Undo do; saving; the language the check
uses; the card and its content; strings other than the caption and the new sample line.

## How it works

- `ui/notes/NotesLayout.kt`: `notesParts(needsKey, checking, hasOthers)` lists the tab's
  parts in order (`AddKey`, `Editor`, `CheckCard`, `OtherNotes`, `Footer`).
- `ui/home/NotesScreen.kt` draws them. `NoteEditor` is the header, the editor and its action
  row; `NoteCheckCard` is the card, outside the editor card.
- `NotesViewModel.showingSample` is true while the editor holds the untouched sample, the
  same rule that keeps the sample from being saved.
