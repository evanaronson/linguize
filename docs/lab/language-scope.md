# Language scope

The writer's current language is app-level context, not a filter on one screen. One switch
in the top bar sets it, and Chat, Write, Progress and History all follow it.

## The problem

Progress had its own Catalan/Spanish switch, but only Words and Your conjugations followed
it. Practice, Your patterns, the Phrasebook and Time spent counted every language, so the
top of the page said "Catalan" and the bottom didn't. The chat had a third language button
of its own, and History had none. The information hierarchy was wrong: a person writing in
Catalan this week is in Catalan everywhere, not on one card.

## How to use it

- **The switch.** The top bar of Chat, Write, Progress and History (and of an open
  conversation) shows the current language's small flag and "▾". Tap it for the menu: each
  of your languages with its flag and name, the current one ticked. Screen readers hear
  "Language: Catalan".
- **Which languages.** Your writing profiles' languages (Catalan, Spanish), then any other
  language found in your History, most used first. A language without a flag drawn gets a
  small code badge ("EN").
- **Flags.** Catalan is the senyera. Spanish follows the variety in your Spanish writing
  profile: Peninsular is Spain, Mexican is Mexico, Rioplatense or Argentinian is Argentina,
  Colombian is Colombia, anything else Spain. The mapping is one small file
  (`ui/language/LanguageFlags.kt`) so it can be swapped for code badges.
- **The default.** Until you switch, the current language is the chat's start language: the
  one you last used in the chat, else the only language your selection menu names, else the
  one you check most, else Spanish (`ChatStart.language`). A switch is kept.

What follows the switch:

| Place | Scoped how |
|---|---|
| **Chat** | The list shows only the current language's conversations; New conversation starts in it (the sheet can pick another). Switching while a conversation is open goes back to the list. A conversation's language never changes once created; opening one in another language (from History) makes that the current language. |
| **Write** | The editor checks in the current language. The language picker by Check can change it (or pick Auto Detect) until you switch the app's language. |
| **Progress** | Every section and the page it opens: Practice (due count and runs), Words, Your conjugations, Your patterns, Phrasebook (list and count), Time spent. Progress has no switch of its own any more. |
| **History** | Starts on the current language. Its language dropdown also offers **All languages**, combined with the source dropdown. All languages lasts until the app's language changes. |

Not scoped: checks from other apps (selection menu, accessibility button, Quick tile,
share). They detect the language as before; the result simply files under the detected
language.

## Data with no language

A check with no reliable detection, a phrase saved without a language, or a time record of
an unknown language belongs to no language. Scoped views leave it out rather than guess;
History's **All languages** shows it. Practice items take the language of their History
attempt, so they are the same.

## How it works

- `core/language/LanguageScope`: pure rules. What the switch lists, the current language
  from a choice and a fallback, and `keeps(language, scope)`: a null scope is All, and
  unknown data only shows under All.
- `data/language/LanguageScopePreferences`: the choice, in Lab's own preferences file
  `lab_language_scope`. Only a switch is stored, never the computed default.
- `ui/language/AppLanguage`: the app's one instance (`AppLanguage.of`). `current` and
  `options` are state flows; `load()` works out the default and the options; `select()`
  keeps a switch. `LanguageSwitch` and `LanguageFlag` draw the switch.
- `ui/components/FilterDropdown`: the one dropdown every filter uses, the language switch
  included (see the rule in [Copy style](../copy-style.md)).
- `HomeActivity` puts the switch in the top bar of the four tabs, passes the current
  language to Chat, Write, Progress, History and Time, and sets it on the Practice,
  Patterns, Words, Conjugations and Phrasebook view models. A switch while a conversation
  is open calls `Navigation.afterLanguageSwitch()`, which returns to the list.
- View models: `PracticeState.language` (due and runs from `scoped`), `PatternsLab.load(language)`
  (reports only that language's checks; the saved explanation is still reconciled against
  every language, so switching never deletes it, and it shows only for the language it was
  made from), `WordsState.shownLanguage` (the scoped language even with no words yet),
  `ConjugationsState.shown` (a note for languages it doesn't cover), `PhrasebookViewModel.setLanguage`
  and `inLanguage`, `TimeReport.of(…, language)` (estimates still use your speed across all
  languages), `historyRows(rows, filter, language)`, and `writeCheckLanguage`.
- Chat: `ChatViewModel` takes `currentLanguage` and `onLanguage`; `ChatStart.inLanguage`
  filters the list. See [Home and navigation](home-ia.md) and [Chat](chat.md).

## Limits

- Export as text in the Phrasebook still exports every language.
- A Patterns explanation made before this change covered every language; it shows again
  only when its sources are all in one language, or after Explain is asked again.
- Your conjugations covers Spanish and Catalan; another language shows a note.
- Languages found in the data come from the newest 200 History rows.
