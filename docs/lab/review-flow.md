# Review flow

Reviewing a check is a walk through its changes, in order: read, tap Accept, tap Accept,
tap Accept. The card opens on the first change, Accept moves to the next one, and when
every change is decided the selection clears and Replace (Send in the chat) is the next
thing to tap. Tapping any underline still jumps straight to that change. Rewordings come
first, then the fixes they didn't already include; once everything is accepted the card is
just the final text, "5 changes accepted" with Undo, and Replace.

## The user moment

The writer has just checked a message in another app, in the chat or in Notes. Before,
they had to find each underline and aim a tap at it, then hunt for the next one, and
deleted words lived apart from the text because there was nothing in it to tap. The
Writing pad's "Next change" showed how much calmer a sequence is. Now the card leads.

## How it works for the writer

- **First change open on arrival.** A result opens with its first change selected and its
  explanation (Accept, the reason, the meters) under the text. That is the first rewording
  when there is one: rewordings are walked first, then the fixes still on offer (see
  Order, below).
- **Accept moves on.** The next open change is selected, in the same frame. After the last
  one the walk wraps once to any earlier change still open (one the writer jumped past).
- **Skip moves on without accepting.** Skip sits beside Accept. The skipped change shows
  its original words again, unmarked, and can be tapped later; the walk doesn't come back
  to it on its own.
- **Done.** When every change is accepted or skipped the selection clears and the card
  scrolls its last row, "2 changes accepted, Undo, Replace", into view. When every change
  is accepted (or covered by an accepted rewording), the card is in its finished state (see
  Finished, below).
- **Partly done.** A kind with nothing left to decide becomes one quiet line with a check:
  "2 rewordings accepted", "3 fixes accepted", "1 fix covered by a rewording", or "2 fixes
  accepted, 1 covered by a rewording". Its accepted changes already show, plain, in the
  other kind's text, so its own text would only repeat it. A kind with a skipped change
  keeps its text: the skipped change is still there to tap.
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

## Order: rewordings first

The owner, after real use: you correct a fix, maybe typing it with
[Type to accept](type-to-accept.md), and then a later rewording rewrites that same stretch,
so the fix was work for nothing. The walk used to go fixes first, then rewordings.

**The rule.** Rewordings first, then the fixes still on offer, each kind in reading order
(the edit engine's text order: by position in the original, an insertion before a
replacement at the same place). The card shows the two kinds as two passages, in the same
order, rewordings on top, so the walk still goes down the card and moves between passages
once. Walking strictly by position would jump between the passages at every step.

**Why it is safe.** The data model already worked this way round. A rewording is written on
top of the corrected text and records the fixes it includes (`Edit.includes`, from
`Edits.naturalResult`); a rewording and a fix that overlap or share an included fix
"touch". Accepting a rewording *retires* the fixes it touches (`Revision.isRetired`): they
leave `remaining`, so they leave the walk, the counts and Accept all. So:

- **A fix inside an accepted rewording** is never offered: the rewording already has it.
- **A fix outside every accepted rewording** is offered after the rewordings, as before.
- **A fix inside a skipped rewording** is offered: skipping isn't a decision, the rewording
  stays on offer, and the fix is the writer's way to repair those words without taking the
  rewording.
- **Both, out of order.** If the writer taps ahead and accepts a fix, then goes back and
  accepts the rewording that includes it, the rewording wins and the fix is retired, as
  before. Rewordings never overlap each other (the engine withholds both), so a fix is
  covered by at most one.
- **Undo** of a rewording brings it back open, and the fixes it covered come back on offer
  after it. Undo of Accept all on the rewordings reopens the first one.
- **Accept all** on the rewordings retires every fix they cover and moves the walk to the
  first fix still on offer. Accept all on the fixes while a rewording is open leaves it open.

**Spoken position.** "Change 1 of 5" counts every change, in the walk's order. Accepting a
rewording takes the fixes it covers out of the total ("Change 3 of 4" next), the same rule
as before: only a fix retired by an accepted rewording leaves the count. The fixes heading
counts the same way ("1 fix" once the other one is covered).

**History stays honest.** Nothing new is recorded and the schema is unchanged. What the
writer accepted is Accepted; a fix covered by an accepted rewording is Retired, which History
shows as "Covered by a rewording", exactly as when a rewording was accepted over a fix
before; a fix inside a skipped rewording is decided on its own (Accepted, or Not accepted).

**The rewordings' text.** It is still read on the corrected text: the fixes still on offer
are applied under it, unmarked, as Copy with rewordings takes them, and a fix the writer
skipped stays as written. The fixes' text, below, marks them. Once the rewordings are
decided, the fixes' text carries the accepted rewordings, plain, and the rewordings' section
is one line.

## Finished

The owner, after accepting everything: the card showed the fixes section and the
rewordings section, each with "All changes accepted", the cleaned-up text twice (once in
each section), and "3 changes accepted" under them. Several ways of saying one thing.

At that moment the writer needs four things: what will be sent (or put back), one short
summary, a way back (Undo), and the next step (Replace; Send in the chat; in Notes, Replace
puts it in the note, with Copy and Share above). So the finished card is:

- **The final text, once**, as it will be sent, with the copy button at its end ("Copy
  text" for screen readers).
- **One row**: "5 changes accepted", Undo, and Replace (Send). The count is the accepted
  changes that show in the text: a covered fix is part of its rewording, not a change of
  its own.
- **No per-kind sections**: no counts, no "All changes accepted", no Accept all.
- **Meaning** stays at the card's foot, collapsed, as always.

Finished means every change is accepted or covered. If the walk ended with something
skipped, the card is only partly done: the kind with the skipped change keeps its text, so
the change can be tapped, and a fully decided kind is its one line. The incomplete-check
notice ("Some fixes are unavailable", Check again) stays above the finished text when there
is one. Undo from the finished state brings the last change back, open, and the sections
with it. While a new check runs, or after it failed, the accepted changes show the same way
(the text, then the row).

## Leave as written

The gap: from another app, a check that found nothing ("Looks good") had no Replace, so the
writer just closed the card, and nothing was saved. Their correct words and conjugations never
reached Words, Conjugations, Patterns or Time spent, which all read History. In the chat every
sent message is saved, unchanged ones too ("Sent as written"); the popup had no equivalent.

- **A clean check** from another app (selection menu, accessibility button, share, quick tile)
  ends with one filled button under "Looks good": **Save**. It closes the card, leaves the
  text where it is, and with History on saves the check unchanged and visible (outcome
  `as_written`, see [History](../history.md#what-is-recorded)). With History off it saves
  nothing and only closes, so it says **Close** there.
- **Every change skipped.** The same gap: a walk that ended with everything skipped and nothing
  accepted had no Replace either. Once the walk is over that way, the button sits where Replace
  would ("…, Undo, Replace" has nothing to count), and is brought into view the same way. The
  check is saved unchanged with its changes `ignored` ("Not accepted" in History); the corpus
  still reads the check's own judgment, so a word a skipped fix changes counts as a mistake, not
  as correct. While a change is still open, or once one is accepted, there is no such button:
  Replace is the ending then. The skipped-all case needs Skip, so a read-only card (share) never
  reaches it.
- **Closing any other way** (a tap outside, Back) saves nothing, as before: only the writer's
  explicit confirmation is evidence that the text was right as written.
- **Not in Chat or Notes.** The chat already ends in Send as written; Notes and Try it save
  nothing by design. Their hosts pass no action, so the card shows no button
  (`CardActions.onLeaveAsWritten`, null by default).
- **Not on a read-only selection.** The selection menu shows it only when the text can be put
  back: a read-only selection is usually a message the writer received, not wrote.

**Why "Save" (and "Close").** The button first said "Leave as written". The owner: "that
sounds like you are contemplating changing it; just say Save since that's all the button is
doing." The card's last button says what it does (Replace, Send; not Done or OK,
[copy style](../copy-style.md)), and here the only thing it does is save the check: the text
stays where it is either way. With History off it saves nothing, so "Save" would be false; it
says "Close", which is all it does then (`CardActions.leaveAsWrittenSaves`, read from History's
setting as the card shows). Not "Keep": copy style keeps "kept" off the idea of saving (that is
"saved"). The feature keeps its name, Leave as written, in these docs and the code. History
still says "Left as written" under the text: next to a button that says Save it reads as what
was saved (the text, unchanged), matching the chat's "Sent as written".

## Decisions

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

- `ui/card/ReviewQueue.kt`: `reviewOrder`/`reviewQueue` (the order: `walkOrder` puts
  rewordings before fixes, whatever order the check lists its kinds in), `ReviewFocus` (a pure
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
- `ui/card/ReviewContent.kt`: `Suggestions` shows the sections in `walkOrder`; a section with
  nothing left (`remaining` empty) is its one line (`resolvedSummary`); when nothing is left
  in any, the finished card (`AcceptedText`: the working text with its copy button, then
  `AcceptedRow`). `RetainedChanges` (checking again, failed) uses the same `AcceptedText`.

Tests: `ReviewQueueTest` (the order, covered fixes never offered, a fix inside a skipped
rewording offered, Undo and Accept all, the spoken position), `ReviewOrderRecordingTest` (a
walk recorded as History records it: Accepted, Retired for a covered fix, a fix inside a
skipped rewording on its own), `ReviewFlowCardTest` (the walk in every host),
`ReviewFinishTest` (the finished card in every host, the partial lines, the sections' order;
each state composed fresh), `OneChangeViewTest`, `TypeToAcceptCardTest`,
`TypeToAcceptLayoutTest`, `WithheldReviewTest`.

Every host (popup over other apps, the selection-menu card, Chat, Notes, the clipboard
card) shows the same `ResultCard`, so the flow is identical everywhere. Nothing new is
recorded: History still sees only accepts, Accept all, Undo, copies and the final text.
A skip is not a decision and isn't saved; Save (Leave as written) after skipping everything
saves the check with those changes not accepted.

Leave as written: `CardActions.onLeaveAsWritten` (the hosts: `CheckActivity`, `ShareActivity`,
`ClipboardActivity`, the accessibility card), `LeaveAsWrittenRow` in `ReviewContent.kt`,
`ReviewFlowUi.allSkipped`, `CheckViewModel.leaveAsWritten`, `CheckHistory.close(asWritten)` and
`Outcome.AsWritten`. Tests: `LeaveAsWrittenCardTest` (the button, Save, on a clean card and
after skipping everything; Close with History off; none without the action; Replace once
something is accepted),
`LeaveAsWrittenTest` (saved unchanged and visible, read by the corpus; nothing saved without it,
with History off, from Notes' origin, or with something accepted; Chat unchanged; older records'
visibility unchanged), `ClipboardActivityTest`, `CheckViewModelTest` (the selection menu hands
nothing back and saves it).

## Limits

- The selection is UI state: it resets when the card is recreated (rotation, process
  death), opening on the first open change again.
- Skipped changes aren't revisited automatically; the writer taps them. Unmarked, they are
  easy to forget; Accept all and the count above the text still include them.
- While the rewordings are walked, their text has the fixes still on offer applied unmarked
  (it's read on the corrected text); the fixes are marked only in the fixes' text below.
- When the rewordings are decided their passage folds into one line, so the fixes' text moves
  up as the walk reaches it.
- The struck old words and a deleted word's mark take room, so the drawn line can wrap
  differently from the final text, and the text reflows as a change is accepted, skipped or
  brought back.
- Read-only cards (no Replace) still open on the first change, but can only be tapped.
