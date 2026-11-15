# Review flow

Reviewing a check is a walk through its changes, in order: read, tap Accept, tap Accept,
tap Accept. The card opens on the first change, Accept moves to the next one, and when
every change is decided the selection clears and Replace (Send in the chat) is the next
thing to tap. Tapping any underline still jumps straight to that change.

## The user moment

The writer has just checked a message in another app, in the chat or in Notes. Before,
they had to find each underline and aim a tap at it, then hunt for the next one, and
deleted words lived apart from the text because there was nothing in it to tap. The
Writing pad's "Next change" showed how much calmer a sequence is. Now the card leads.

## How it works for the writer

- **First change open on arrival.** A result opens with its first change selected and its
  explanation (Accept, the reason, the meters) under the text.
- **Accept moves on.** The next open change is selected, in the same frame. After the last
  one the walk wraps once to any earlier change still open (one the writer jumped past).
- **Skip moves on without accepting.** Skip sits beside Accept. The skipped change shows
  its original words again, unmarked, and can be tapped later; the walk doesn't come back
  to it on its own.
- **Done.** When every change is accepted or skipped the selection clears and the card
  scrolls its last row, "2 changes accepted, Undo, Replace", into view.
- **Undo goes back.** Undo reopens the change it brought back, ready to decide again.
- **Tap to jump.** Tapping (or a keyboard link, or a screen reader's "Why" action) selects
  any change; Accept then continues from there. Tapping the open change closes it.
- **Every change in place.** A replaced word shows its old words struck through, then the
  new words, with the change's underline under both (see One change view, below). A deleted
  word stays in the text, struck through and underlined, where it was. A line break shows
  as ¶ (struck through when it goes), other spacing as a caret ‸. These marks are tapped,
  tinted and walked like any other change. The separate block that used to hold them is
  gone. Copy and Replace never include the old words or the marks: only the drawing changes.

## One change view

The owner, on a past chat message: it "looks better, but why is it not used normally". The
past message wrote each change in place, old words struck through and then the new ones,
and listed every change under the text; the card showed only the new words, underlined,
and the explanation of the open change only. Now there is one way, everywhere: the
past message's drawing and the card's interaction.

How each change looks in the card's text:

| State | Looks like |
|---|---|
| **Pending** | The old words struck through in grey, then the proposed new words, with the change's underline under both: solid proof vermilion for a fix, dotted grey for a rewording. A deletion is just its struck word; an insertion just its new words; spacing its ¶ or ‸. |
| **Current** (selected, open) | The same, on the faint selected tint. Its explanation is the only one open, under the text. |
| **Accepted** | Just the new words, plain, as they will be sent. No strike, no underline. Undo brings the change back as pending. |
| **Skipped** | The original words, plain, as they will be sent: no strike, no proposed words, no underline. A skipped change with nothing visible to keep (an insertion, added spacing) leaves a quiet grey ‸ (or ¶) in its place. A tap on the words (or the caret) brings the change back, proposed again; the screen reader's "Why" action and Accept all still reach it. |
| **Typed correctly** (Type to accept) | During the hold the struck old words fade out, the underline lets go and the new words glow briefly; then it is accepted. See [Type to accept](type-to-accept.md). |

**Why accepted shows only the new words.** The text converges on what will be sent: as
each change is decided, the sentence reads more like the final message. Keeping the struck
old words, lighter, would leave every accepted change looking almost like a pending one,
and "what will be sent" would need reading twice. Undo, under the text, still shows that
something was accepted ("2 changes accepted"). It also makes the Type to accept beat honest:
the old words fading out is the change becoming accepted.

**Why skipped shows the original.** It's what will be sent for it. A skip isn't a decision
(Accept all still takes it), so it stays reachable: the words are still a link, and a
skipped insertion keeps a caret to tap.

**Read-only everywhere else.** A sent chat message ([Chat](chat.md)) and a saved check in
History ([History](../history.md)) show the same component, `HighlightedText`, read-only
through `ChangeRecord`: what was written, each change in the pending look (the old words
struck, then the new), nothing to accept or copy, and a tap on a change opens its
explanation in the card's own `ExplanationPanel` (the change, the reason, the meters when
saved, Teach me, the Phrasebook star). One open at a time, and no list of every change. In
History the panel also says what became of the change ("Accepted", "Not accepted",
"Covered by a rewording") on a quiet line. A record shows both kinds in one text; where a
fix and a rewording overlap (a fix, then the rewording that replaced it), it shows the
card's two texts, fixes then rewordings on the corrected text, each with its count.

## Decisions

**Order: screen order.** Fixes first, then rewordings, each in reading order (the edit
engine's text order: by position in the original, an insertion before a replacement at
the same place). The card shows fixes and rewordings as two passages of text, so walking
strictly by position would jump between the passages at every step and open explanations
above and below the reader's eye. Fixes-first also matches how the engine resolves
overlap: rewordings are written on top of the corrected text, so a fix comes before the
rewording that overlaps it, and accepting the fix never loses the rewording (accepting the
rewording instead retires the fix, which then drops out of the walk).

**"Skip", not "Not now".** Copy style asks buttons for a verb. Skip says exactly what
happens (move on, nothing accepted) and leaves "Not accepted" as History's word.

**No prev/next buttons, no visible "2 of 5".** Skip already is "next"; tapping is "back"
and "jump". A visible counter competes with the section counts ("3 fixes") that already
say how much there is. Screen readers, which can't see where the tint went, do hear the
position: the open change is announced as "Change 2 of 5. estas becomes estàs". The
number counts every change in the check, accepted ones included, so it doesn't renumber
as changes are accepted (the owner's worry about counters); only a fix retired by an
accepted rewording leaves the count.

## Accessibility

- The explanation panel is a polite live region, so each newly selected change is read
  out with its position.
- Per-change actions stay on the text ("Why remove yo", "Remove yo", ...), plus "Skip
  this change" while a change is open.
- Screen readers hear the real suggested text, not the struck old words or the marks; each
  change is described in words in its actions ("Why estas becomes estàs") and in its
  explanation ("estas becomes estàs"). A past chat message is heard as "You wrote: …".

## How it's built

- `ui/card/ReviewQueue.kt`: `reviewOrder`/`reviewQueue` (the order), `ReviewFocus` (a pure
  state machine: `start`, `select`, `skip`, `follow` a revision change, `position`,
  `isDone`), and `ReviewFlow`, which keeps one focus per check and follows the revision as
  it is read, so the next change opens in the same frame as the accept.
- `ui/card/ReviewFlowCard.kt`: `rememberReviewFlow` for the suggestions page; keyed on the
  check's text and edits, so a new answer (after a clarification or retry) starts again at
  its first change, with accepted changes carried over as before.
- `ui/card/ChangeMarkers.kt`: `markChanges` writes every change into the drawn text (old
  words struck, then the new; deletions and spacing as marks; skipped changes as their
  original words) and maps each change's range into it, with its struck part and its new
  words (for the Type to accept beat).
- `HighlightedText` takes an optional shared `ReviewSelection` (with the skipped changes);
  without one it keeps its own selection, as before. `ExplanationPanel` takes `onSkip`, a
  spoken `position` and, for History, a `status` line. With `onReplace` and `onCopy` null it
  is read-only; a `Highlight` can carry its own `kind`, so one text can hold fixes and
  rewordings.
- `ui/card/ChangeRecord.kt`: `recordPassages` turns a past check's changes (spans in the text
  as written) into one text, or the card's two when they overlap, and `ChangeRecord` shows
  them read-only with one shared selection. Chat's `ChatCorrections` and History's
  `SavedSuggestions` both use it, so they can't drift from the card.
- `Revision.previewOnCorrected(kind, skipped)`: the rewordings' text leaves skipped fixes as
  written, so both texts show what will be sent. Copy still takes every remaining change.

Every host (popup over other apps, the selection-menu card, Chat, Notes, the clipboard
card) shows the same `ResultCard`, so the flow is identical everywhere. Nothing new is
recorded: History still sees only accepts, Accept all, Undo, copies and the final text.
A skip is not a decision and isn't saved.

## Limits

- The selection is UI state: it resets when the card is recreated (rotation, process
  death), opening on the first open change again.
- Skipped changes aren't revisited automatically; the writer taps them. Unmarked, they are
  easy to forget; Accept all and the count above the text still include them.
- The struck old words and a deleted word's mark take room, so the drawn line can wrap
  differently from the final text, and the text reflows as a change is accepted, skipped or
  brought back.
- Read-only cards (no Replace) still open on the first change, but can only be tapped.
