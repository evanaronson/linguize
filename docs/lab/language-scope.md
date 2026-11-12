# Language scope

The writer's current language is app-level context, not a filter on one screen. One
selector in the top bar of the four tabs sets it, and Chat, Notes, Progress and History all
follow it. It offers the product's own names: **Linguize** (Auto, the default),
**Catalanize** (Catalan) and **Castilianize** (Spanish).

## The problem

Progress had its own Catalan/Spanish switch, but only Words and Your conjugations followed
it. Your patterns, the Phrasebook and Time spent counted every language, so the
top of the page said "Catalan" and the bottom didn't. The chat had a third language button
of its own, and History had none. The information hierarchy was wrong: a person writing in
Catalan this week is in Catalan everywhere, not on one card.

The first fix added one switch in the top bar but left pickers beside it: Write's old "Auto
Detect ▾" by Check, the new-conversation sheet's language dropdown and History's "Catalan ▾
/ All languages ▾". Four controls could disagree about the language, and the switch had no
way to say "all of them", which is what most people want most of the time. Now there is
one selector, and "all" is its default.

## How to use it

- **The selector.** The top bar of Chat, Notes, Progress and History shows, just before
  the Settings gear, the current choice's small icon and "▾". Tap it for the menu:
  **Linguize** with its "A" tile (Auto-detect, every language), **Catalanize** with the senyera,
  **Castilianize** with the Spanish flag, then the writer's other languages with a code
  badge ("FR"); languages found in History but not added are only suggested
  ([Any language](language-generic.md)). Each brand name has what it means under it
  ("Auto-detect", "Catalan", "Spanish"); the current one is ticked. Screen readers hear
  "Language: Catalanize, Catalan".
- **Where it isn't.** Pages opened from a tab (a conversation, Words, a saved check,
  Settings) show Back and their title, no selector. A conversation's language is fixed
  once created; a page Progress opens shows the language it was opened in. No screen,
  sheet or card inside the app has a language choice of its own.
- **Flags.** Catalan is the senyera. Castilianize follows the variety in your Spanish
  writing profile: Peninsular is Spain, Mexican is Mexico, Rioplatense or Argentinian is
  Argentina, Colombian is Colombia, anything else Spain. The mapping is one small file
  (`ui/language/LanguageFlags.kt`) so it can be swapped for code badges.
- **The default** is Linguize. A choice is kept. A language chosen before Linguize was an
  option is kept as it is.

What each choice means:

| Place | Linguize (Auto) | Catalanize, Castilianize (or another language) |
|---|---|---|
| **Chat** | The list shows every conversation, each with its language's flag. The app opens to the latest conversation, whatever its language. New conversation starts in the chat's start language: the one you last used in the chat, else the only language your selection menu names, else the one you check most, else Spanish (`ChatStart.language`). Nothing is asked. | The list shows only that language's conversations; the app opens to its latest; New conversation starts in it. |
| **A conversation** | Its own language. Opening one doesn't change the selector. | Its own language. Opening one in another language (from History) makes that the current language, so Back shows its list. |
| **Notes** | Checks detect the language. | Checks are in that language. A text in another language gets the card's "This isn't Catalan", with "Choose Linguize at the top to detect the language." |
| **Progress** | Words, Your conjugations and Your patterns are grouped once per language in use, each block headed by its brand name and icon; a row opens its page in that block's language. Time spent and the Phrasebook cover every language; their pages break it down by language. | Every section, and the page it opens, shows only that language. |
| **History** | Every message, any language, including ones with no known language. | Only that language's messages. |

Not scoped: checks from other apps (selection menu, accessibility button, Quick tile,
share). The app's choice doesn't reach them. They detect the language, or use the
Catalanize or Castilianize selection-menu entry, and the popup card keeps its own picker
(Auto-detect, Catalanize, Castilianize). The result files under the detected language.

## Data with no language

A check with no reliable detection, a phrase saved without a language, or a time record of
an unknown language belongs to no language. Scoped views leave it out rather than guess;
Linguize shows it (History and the Phrasebook pages).

## How it works

- `core/language/LanguageScope`: pure rules. Null is Auto everywhere. `current(stored)`
  reads a stored choice (a code is kept, anything else is Auto), `stored(language)` writes
  one (`auto` for Linguize), `options(data, current)` is the selector's order (Auto,
  `ca`, `es`, then others), `inUse(found)` the blocks Progress shows under Auto, and
  `keeps(language, scope)`: a null scope keeps everything, and unknown data only shows
  under it.
- `data/language/LanguageScopePreferences`: the choice, in Lab's own preferences file
  `lab_language_scope`. Nothing is stored until the first choice.
- `ui/language/AppLanguage`: the app's one instance (`AppLanguage.of`). `current` (null is
  Linguize) is known at once from what was stored; `options` lists the languages after
  Linguize, with the profile's variety for Castilianize's flag; `select()` keeps a choice;
  `follow()` is what opening a conversation does.
- `ui/language/LanguageSwitch`: the selector (`LanguageSwitch`, `LanguageIcon`,
  `brandName`, `brandNameResource`), drawn with `ui/components/FilterDropdown` (its
  `detail` line says what a brand name means). Only `HomeActivity` calls it, and
  `AppNavigation` draws it only on a root tab.
- `Navigation` saves either a legacy page following the app or a `Screen.Scoped` page
  with its `RouteScope.Block` language (`scoped:ca:patterns`, for example). The block
  survives rotation and a fresh process under Auto. Pattern detail inherits that scope;
  Word and Conjugation detail routes already carry their own language.
- `HomeActivity` resolves the visible route and passes its language to the feature's
  entry effect before loading. It does not broadcast scope to
  unrelated view models. Auto still means all languages for Patterns and the
  Phrasebook; an unscoped Words or Conjugations page keeps its last/default language.
- An actual change of the app language clears block overrides across the live path,
  including ancestors behind a saved check; the initial Flow emission does not.
  Switching from Auto to Spanish and back therefore follows Auto again rather than
  reviving an old Catalan block. Intrinsic Word/Conjugation details keep their language.
  Chat's language-follow action clears overrides without closing that conversation;
  the selector's explicit switch retains its existing close-to-list rule.
- View models: `PatternsLab.load(language)`, `WordsState.shownLanguage`,
  `ConjugationsState.shown`, `PhrasebookViewModel.setLanguage` and `inLanguage`,
  `TimeReport.of(…, language)`, `historyRows(rows, filter, language)`,
  `noteCheckLanguage`, and `progressLanguages(current, found)`.
- Chat: `ChatViewModel` takes `currentLanguage` and `onLanguage` (`AppLanguage.follow`);
  `ChatStart.inLanguage` filters the list (null keeps all). See
  [Home and navigation](home-ia.md) and [Chat](chat.md).
- The card's wrong-language hint depends on where it is (`ui/card/LanguageHint`): the
  picker in a popup, the selector in Notes, nothing to choose in a conversation.

## Limits

- Export as text in the Phrasebook still exports every language.
- Under Linguize, a Your patterns row in a language's block shows the general summary, not
  that language's top group (the report is built for one scope at a time).
- A Patterns explanation made before the language scope covered every language; it shows
  again only when its sources are all in one language, or after Explain is asked again.
- Your conjugations covers Spanish and Catalan; another language's block has no row for it.
- Languages found in the data come from the newest 200 History rows.
