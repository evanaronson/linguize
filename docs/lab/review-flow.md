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
- **Skip moves on without accepting.** Skip sits beside Accept. The skipped change keeps
  its underline and can be tapped later; the walk doesn't come back to it on its own.
- **Done.** When every change is accepted or skipped the selection clears and the card
  scrolls its last row, "2 changes accepted, Undo, Replace", into view.
- **Undo goes back.** Undo reopens the change it brought back, ready to decide again.
- **Tap to jump.** Tapping (or a keyboard link, or a screen reader's "Why" action) selects
  any change; Accept then continues from there. Tapping the open change closes it.
- **Deletions and spacing in place.** A deleted word stays in the text, struck through and
  underlined, where it was. A line break shows as ¶ (struck through when it goes), other
  spacing as a caret ‸. These marks are tapped, tinted and walked like any other change.
  The separate block that used to hold them is gone. Copy and Replace never include the
  marks: only the drawing changes.

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
- When the text carries marks, screen readers hear the real suggested text, not the marks.

## How it's built

- `ui/card/ReviewQueue.kt`: `reviewOrder`/`reviewQueue` (the order), `ReviewFocus` (a pure
  state machine: `start`, `select`, `skip`, `follow` a revision change, `position`,
  `isDone`), and `ReviewFlow`, which keeps one focus per check and follows the revision as
  it is read, so the next change opens in the same frame as the accept.
- `ui/card/ReviewFlowCard.kt`: `rememberReviewFlow` for the suggestions page; keyed on the
  check's text and edits, so a new answer (after a clarification or retry) starts again at
  its first change, with accepted changes carried over as before.
- `ui/card/ChangeMarkers.kt`: `markChanges` draws deletions and spacing as marks and maps
  each change's range into the drawn text.
- `HighlightedText` takes an optional shared `ReviewSelection`; without one it keeps its
  own selection, as before. `ExplanationPanel` takes `onSkip` and a spoken `position`.

Every host (popup over other apps, the selection-menu card, Chat, Notes, the clipboard
card) shows the same `ResultCard`, so the flow is identical everywhere. Nothing new is
recorded: History still sees only accepts, Accept all, Undo, copies and the final text.
A skip is not a decision and isn't saved.

## Limits

- The selection is UI state: it resets when the card is recreated (rotation, process
  death), opening on the first open change again.
- Skipped changes aren't revisited automatically; the writer taps them.
- A deleted word's mark adds a space next to a neighbouring word, so the drawn line can
  wrap differently from the final text.
- Read-only cards (no Replace) still open on the first change, but can only be tapped.
