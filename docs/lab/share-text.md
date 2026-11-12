# Share text to Linguize

## What it is

Linguize appears in Android's share sheet for text. Sharing opens a small floating
chooser over the app you shared from: **Check it** runs the usual check card,
**Understand it** runs the usual Understand card (translation and notes).

## The moment it serves

Some apps let you share text but not select it: many reader apps, email clients, and
browsers' "Share" for a selection. Sharing gets that text into Linguize, and the chooser
lets the person say what they want: check their own writing, or understand someone
else's.

## How to use it

In any app, Share → Linguize X Claude → **Check it** or **Understand it**. The check card
offers Copy with fixes and Copy with rewordings; shared text can't be put back in the
source app, so there's no Replace. Back returns to the chooser.

## How it works

- `menu/ShareActivity` handles `ACTION_SEND` with `text/plain` (overlay theme). It reuses
  `CheckViewModel`, `FloatingCard`, `UnderstandViewModel` and `UnderstandCard`.
- `menu/SharedContent.text` reads the shared text, trimmed. A malformed intent counts as
  nothing shared, and the activity closes with "No text to check".
- Coming back from the app or Settings rechecks with the new settings.

## Limits

- A shared-text check is recorded in History as a selection-menu check (no source app is
  known), because History has no separate origin for shares. Understand is never
  recorded.
- The Understand card's labels say "Copied text" even when the text was shared.
- Images aren't accepted. Understand a photo was removed: a general assistant does it
  better, and it isn't part of the writing loop.
