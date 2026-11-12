# Type to accept

Accepting a change by typing it. A switch in **Settings › Checking**, off by default.

## The problem

Accepting a change is one tap. The text gets better, but the writer doesn't: a tap on
Accept doesn't make them notice that *estas* became *estàs*, so they write *estas* again
tomorrow. The owner wanted a way to make the correction pass through their own fingers.

## How it works for the writer

With **Type to accept** on, every check card (the popup over other apps, the selection-menu
card, Chat's review panel, Notes, the clipboard card: they all share `ResultCard`) works
like this:

- The [review flow](review-flow.md) still opens on the first change and walks them in
  order. On the open change, the **Accept** button is replaced by a field. The field takes
  focus and the keyboard opens.
- The writer types the change's new text, the bold part of "estas → **estàs**".
- The field compares as they type. While the text is still the start of the change,
  nothing happens. The moment one character is off, the field turns to the error state
  (the theme's error colour, outline and message): "Not quite: check the change above".
  Fixing the character clears it.
- As soon as the text matches exactly, the change is accepted, with no extra tap. It is
  the same Accept as the button's: the same History recording, the same move to the next
  change, whose field opens empty and focused.
- **Skip** stays beside the field, as does the Phrasebook star.
- With the switch off, the card is exactly as before.

## Decisions

**What to type.** The change's new text as the card shows it: line breaks inside it read
as one space, and spaces at either end are dropped. Spaces inside count ("de la", not
"dela").

**Exact characters.** Case, accents and punctuation all count: accents are often the
lesson, so *estas* and *estás* are both "not quite" for *estàs*. Two exceptions, both
about how text is encoded rather than what it says:

- *Unicode forms are the same.* é typed as one character, or as e followed by a combining
  accent, is the same é (canonical equivalence, compared in decomposed form). The writer
  isn't told they're off while a letter still waits for its accent (a dead key).
- *Apostrophes are one.* ' (straight), ’ (typographic) and ʼ (modifier letter) all
  match each other. The model writes either, and keyboards type either; making the writer
  hunt for the right one teaches nothing about the language.

**Deletions and spacing keep Accept.** A deletion ("remove yo") has nothing to type, and a
spacing change (a space, a line break) has nothing visible to type. Asking the writer to
type nothing, or a lone space, would be a ritual, not a lesson. So those changes keep the
normal Accept button. An insertion is typed like any other change.

**Long rewordings are typed in full.** A rewording can be a whole clause. The field wraps,
so the writer sees everything they typed, and typed line breaks are dropped. Typing a long
rewording is the point of the setting; Skip and Accept all are there when it isn't worth it.

**What still accepts without typing.** Accept all, under each section, and the screen-reader
action on the text that accepts a change ("Accept amigo to amigos") stay as they are.
Accept all is a deliberate choice to take a whole section at once, and the screen-reader
action is how the card is used without seeing the field. The setting changes the one
tap that happens on every change, not every way to accept.

**Where the switch lives.** Settings › Checking, under What to suggest: both say how checks
behave on the card. Not App, which is about the app's shell (Start on).

## Accessibility

- The field's label names what to type: "Type estàs to accept it". The placeholder says
  "Type the change".
- The error is set on the field (Android's error state) and its message is a polite live
  region, so it is read out as it appears.
- The review flow's spoken position ("Change 2 of 5. estas becomes estàs") is unchanged.

## How it's built

- `data/lab/TypeToAcceptPreferences.kt`: the switch, in its own preferences file
  (`lab_type_to_accept`), never the app's `settings`. Nothing changes in History.
- `ui/settings/TypeToAcceptSetting.kt`: the switch row (`SwitchRow`), placed in the
  Checking card of `SettingsScreen`.
- `ui/card/TypeToAccept.kt`: `TypeToAccept` (the pure rules: `target`, `typed`,
  `progress`), and `TypeToAcceptField`, built on the shared `LinguizeTextField`, which
  accepts through the same `onAccept` as the button, once.
- Small hooks: `Suggestions` reads the switch once per check and passes it through the
  review flow (`ReviewSelection.typeToAccept`). `ChangeExplanation` turns it into a
  `TypeTarget` (the change's id and text) for the open change; `ExplanationPanel` shows the
  field instead of Accept when it has one. The field is keyed on the change's id, so each
  change starts empty. `LinguizeTextField` gained `announceSupporting` for the live message.

Tests: `TypeToAcceptTest` (the rules), `TypeToAcceptCardTest` (off is unchanged; a deletion
keeps Accept; typing, the error and its clearing, accepting and moving on; Skip),
`TypeToAcceptSettingTest` (off by default, persists).

## Limits

- The switch is read when a check is shown. Turning it on while a card is open applies from
  the next check.
- The field opening the keyboard by itself has been checked in tests, not yet on the popup
  over other apps on a device, where the keyboard and the card share the screen.
- Keyboard autocorrect is asked to stay off in the field; some keyboards ignore that.
