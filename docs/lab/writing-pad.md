# Writing pad

A full-screen editor inside Linguize for messages that matter: an email to a
landlord, a note to the school, a cover note. It checks as you write, underlines
changes in the text itself, and keeps drafts on the phone until you copy or share
the finished message.

## The moment it serves

The selection menu serves the second before sending a short message. Longer
messages are different: people write them in a notes app over several minutes,
then paste them somewhere. Selecting a whole email to check it, closing the card,
editing, and checking again is slow, and the card covers the text being edited.

The pad keeps the writer in one place. They write, pause, and the changes appear
as underlines in their own text, the same visual language as the card. Their
voice stays theirs: nothing changes until they accept a change.

## How to use it

1. On Home, tap **Writing pad**, then **New draft** (or open a saved draft).
2. Write. Add a title if you like; otherwise the list uses the first line.
3. Pause. After about 1.5 seconds Linguize checks the text. A solid red underline
   is a fix. A dotted grey one is a rewording.
4. Tap an underline. A panel under the text shows the change, why, and its impact
   and confidence, with **Accept** and **Dismiss**. The keyboard stays open.
   **Next change** in the status line steps through every change, which also works
   with a screen reader (each change is an action on the text field too).
5. **Meaning** at the bottom shows how the message reads in your own language. Tap
   it to open it. It says when it is from an older version of the text.
6. Copy or share from the top bar. Drafts save as you type. Delete one from the
   list or the editor, with Undo.

The language picker sets the language for this draft (Auto Detect by default).
Checks use the provider, model and options from Settings.

## How it works

- **Engine.** `core/writingpad` holds the rules with no Android code:
  - `CheckSchedule` decides when to check: after `idleMillis` of no typing, only
    when the text differs from what was checked, and never the same text twice.
    At most one check runs; starting a newer one cancels the older one, and an
    answer with an old generation is ignored. An edit while a check runs doesn't
    cancel it, because its answer is still useful once moved onto the new text.
    A failed text isn't sent again until the writer edits it or taps Retry.
  - `TextDiff` finds what changed between two versions. Typing changes one place,
    which the common prefix and suffix find exactly. A larger change, like a paste
    over several sentences, is aligned inside that window with the edit engine's
    `Alignment`, so unchanged words between two edits count as unchanged.
  - `LiveSuggestions` turns a check's `Edit`s (from `interpret`, so `Edits` and
    `Links` apply as on the card) into live suggestions, then moves them through
    each change. A suggestion is dropped when a change overlaps it, joins a word
    at its edge ("cami" becoming "camis"), or leaves its text out of place. While
    typing, a suggestion is also dropped when its sentence is edited, since a fix
    can depend on the words around it. A whole new sentence typed in front of it
    doesn't count as editing it. The next check offers it again if it still holds.
  - `PadEngine` puts these together. A check's answer is anchored to the text it
    was asked about and moved onto the text as it is when the answer arrives.
    Accepting a change moves the others with it, so no new check is needed. A
    rewording that contains an accepted fix stays on offer, now replacing the
    corrected words, since rewordings are written on top of the fixes. Accepting a
    rewording retires the fixes it touches or includes, as on the card. Dismissed
    changes stay hidden when a later check offers the same change in the same
    sentence.
- **Driver.** `ui/writingpad/WritingPadViewModel` runs the timer and checks on its
  scope, calling the shared `Checker`. It holds the text field's value, so a tap
  that only moves the cursor into an underline opens that change.
- **Editor.** `LiveTextField` is a `BasicTextField` that draws underlines from its
  own text layout, reusing the card's line geometry.
- **Drafts.** `data/writingpad/DraftStore` keeps drafts in `lab_writing_pad.json`
  in the app's files directory, written through a temporary file and a rename. An
  unreadable file is set aside, not overwritten. It never touches History or
  settings.
- **Entry.** `menu/WritingPadActivity` hosts the pad. Home opens it by class name,
  since `ui` must not import `menu`; a test holds that name to the manifest.

## Limits

- Each check sends the whole draft (up to Linguize's 3,000-character limit), so
  the meaning and context stay right. A long draft costs a full check per pause.
  The pad never sends the same text twice and stops at the first failure.
- Checks here are not saved in History, like checks in Try it.
- Clarifying assumptions and the meaning, which the card offers, are not in the
  pad yet. The assumptions a change rests on are not shown.
- Suggestions dropped by typing return only after the next check, so underlines in
  the sentence being edited disappear until the pause.
- There is no Undo for an accepted change yet. Edit the text back by hand.
- Drafts are on this phone only. Removing Linguize Lab deletes them.
