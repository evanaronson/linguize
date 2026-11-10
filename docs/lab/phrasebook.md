# Phrasebook

Save the phrases Linguize teaches you, and find them again.

## The moment

A check says "me va bien" would sound more natural than "es bueno para mí". The writer
accepts it, puts the text back and returns to the conversation. A week later they want to
say the same thing, and the better wording is gone: History has it, but buried inside one
past check. A rewording or a fix that the writer thinks "I'd like to say it like that
from now on" is worth keeping on its own.

The Phrasebook is the writer's own list of those phrases. It doesn't infer what they get
wrong, and it doesn't quiz them. It keeps exactly what they chose to keep, with the reason
the check gave, in their own language.

## How to use it

- **Save from a check.** Tap a fix or a rewording on the card to open its explanation.
  The star beside Accept saves the phrase. Tap it again to remove it. This works on every
  card: the editor on Notes, the selection-menu popup and the accessibility button.
- **Save from History.** Open a saved check, expand a suggestion, and tap its star.
- **Add one by hand.** On the Phrasebook screen, tap **Add phrase**. Type the phrase and,
  if you like, what you used to write instead and a note in your own language. Pick the
  language, then **Save phrase**.
- **Find it again.** Progress has a **Phrasebook** row with the count, last, under Saved by you ([Progress](ia-progress.md)). The screen lists the
  app's current language's phrases, newest first ([Language scope](language-scope.md)); a
  phrase saved without a language isn't listed under a language. Search covers the phrase, what it replaced and its note, and
  ignores case and accents ("esta" finds "està").
- **Use it.** The copy button copies a phrase. Tap a phrase for **Open check** (when it
  came from a saved check) and **Delete**. You can also swipe it away. Either way,
  **Undo** brings it back.
- **Export.** **Export as text**, at the bottom of the screen, shares every phrase as
  plain text, grouped by language, to any app (notes, email, a flashcard app).

## What each entry keeps

| Field | From a check | Added by hand |
|---|---|---|
| Phrase | The change's new words | Typed |
| Instead of | The words it replaced, if any | Optional |
| Meaning or note | The change's reason, in your own language | Optional |
| Language | The language you picked, or the one Linguize detected | Picked |
| Source | Fix or rewording, the app the text came from, the date | "Added by you", the date |
| Check | The saved check, when saved from History | None |

Saving the same phrase twice (same words, same replaced words, same language, ignoring
case and spacing) keeps one entry. If the first copy had no note, the second fills it in.

## How it works

- **Storage.** `data/phrasebook/Phrasebook.kt`. `PhrasebookStore` keeps a JSON file of
  Lab's own, `files/lab_phrasebook.json`. Each change writes a temporary file, then renames
  it over the old one. A file this build can't read is renamed to `.unreadable` and never
  deleted. Unknown fields are ignored, so a later format can add fields. One store is
  shared by the app (`PhrasebookStore.of`) and exposes its phrases as a `StateFlow`. Every
  star and the Progress count stay in step without refreshing.
- **Save control.** `ui/phrasebook/PhraseSave.kt`. `phraseCandidate` turns a change into
  a `PhraseDraft`. It offers nothing for deletions, spacing, punctuation-only changes or
  text over 300 characters. `SavePhraseButton` is the star: a toggle with its state read
  out as "Saved" or "Not saved". The language and app come from a `PhraseOrigin`, provided
  through `LocalPhraseOrigin`:
  - On a live card, `CardActions.of` builds one from the `CheckViewModel`: the picked
    language, otherwise the language named in the model's answer (`answerLanguage`), and
    the host app.
  - On a saved check, `SessionScreen` provides `PhraseOrigin.of(session)`: the target or
    detected language, the host app and the check's id.
- **Screen.** `ui/phrasebook/PhrasebookScreen.kt` and `PhrasebookViewModel.kt`. The pure
  functions `filterPhrases`, `groupByLanguage` and `phrasebookText` handle search, sections
  and export. Delete removes the phrase at once and keeps it in the view model for Undo.
- **Hooks into shared files.** One `Screen.Phrasebook` entry, the Progress row, an
  optional `phrase` parameter on `ExplanationPanel`, `phraseOrigin` on `CardActions`, two
  read-only properties on `CheckViewModel`, and the providers in `ResultCard` and
  `SessionScreen`. History, its schema and the check prompt are unchanged.

Tests: `PhrasebookStoreTest` (persistence, de-duplication, delete and restore, unreadable
and newer files), `PhrasebookStateTest` (search, grouping, export, which changes can be
saved, language from an answer), `PhrasebookViewModelTest` (loading, search, add, delete
and Undo) and `PhrasebookNavigationTest`.

## Limits

- No inserting from the popup yet. From a popup, the wordmark button opens the app
  without losing the check. From there, the Phrasebook is in Progress, and Copy puts a phrase
  on the clipboard.
- A phrase is the change's own words, not the whole sentence around it. A one-word fix
  ("està") is saved as one word.
- Undo lasts as long as the snackbar and doesn't survive the app's process ending.
- Phrases don't follow History. Deleting a check leaves its phrases, and **Open check** on
  a deleted check says the check was deleted. Uninstalling Lab deletes the Phrasebook.
- Each language has its own section, Catalan and Spanish first, then others by code. A
  phrase whose language wasn't known is listed under "Other".
