# Any language

Linguize started as a Spanish and Catalan app. Nothing about checking, chatting or keeping
History needs it to be: the model reads and writes any language. This change makes the
language model underneath the app general, so any ISO 639-1 code works end to end. Catalan
and Spanish stay what everyone has today, with their brand names, flags and the on-device
engines that only exist for them.

It is plumbing, not a redesign. The language catalog (every ISO 639-1 language with a brand
name such as "Gallicize" and a round flag) and the selector UI (a short list of the writer's
languages plus an "Add languages" screen) are built separately on top of it.

## The user moment

A writer who also writes French (or German, or Japanese) adds it once. From then on the top
bar's selector lists it beside Catalanize and Castilianize, Settings keeps a writing profile
for it, the card's picker offers it, a new conversation can be in it, and Progress, History
and Chat simply show nothing for it until there is something to show. Views that exist only
for Spanish and Catalan say so instead of pretending.

## How to use it

- **The writer's languages** ("my languages") are an ordered list, Catalan then Spanish for
  everyone today. `AppLanguage.add(code)` / `remove(code)` edit it (the "Add languages"
  screen will call these); `AppLanguage.suggested` lists languages found in History that
  aren't added yet, most used first.
- **The selector** (top bar) lists Linguize, then the writer's languages in order, then the
  current language if it was chosen before it was added.
- **Settings, Writing preferences**: "Writing in" lists the writer's languages; register and
  punctuation are per language, as before.
- **The card's picker** in popups (selection menu, share, clipboard, accessibility button)
  offers Auto-detect and the writer's languages (and the pinned language if it is another).
- **Per-app voice**, **Phrasebook** (Add a phrase) and **Words** (Import a deck) offer the
  writer's languages too.

## Inventory: where the app assumed Spanish and Catalan

Found by searching Kotlin, resources, the manifest and the Lab prompt assets (not
`check_prompt.md` or the eval) for Catalan, Spanish, Castil, `"ca"` and `"es"`.

| Area | Assumption | Now |
|---|---|---|
| `core/CheckOptions.kt` `Language` | A closed pair: `Language.all = [Catalan, Spanish]`, `forCode` returned null for anything else, `variety` required | `forCode` resolves any ISO 639-1 code (name from the platform, in English for prompts); `variety` is optional; `all` stays for History's name migration and the default list |
| `core/language/LanguageScope` | `BRANDS = [ca, es]`; options = brands + languages found in History | `DEFAULT_LANGUAGES`; options = the writer's languages (+ current); `suggested`; `inUse(found, mine)` |
| `ui/language/AppLanguage` | Options from brands + History | Options from the writer's languages, kept in step with every change; `mine`, `suggested`, `add`, `remove` |
| `ui/language/LanguageSwitch`, `LanguageFlags` | Brand names and flags for ca/es only | ca/es unchanged; any other language asks `Languages.names.brand` (the catalog), else its own name with a code badge |
| `data/Settings` writing profiles | Exactly one profile each for `Language.all`; other languages' records dropped | One per writer's language, in order; a new language gets the default everyday profile; profiles of removed languages stay stored |
| `data/CheckPreferences` | Default profiles for ca/es | Unchanged (test and host default) |
| `ui/settings` | "Writing in" listed `Language.all`; default Catalan; menu page listed all entries | Lists the writer's languages; falls back to the first; menu page offers pinned entries only for the writer's languages or while on |
| `ui/components/LanguagePicker` | Auto + ca + es | Auto + the writer's languages (+ selected) |
| `data/SelectionMenu`, manifest | Catalanize / Castilianize aliases | Kept (see decision below); `MenuEntry.offered` |
| `ui/chat/ChatViewModel`, `core/chat/ChatStart` | Start language among `Language.all`; Spanish fallback opener | Start language among the writer's languages; fallback openers for common languages, plain "Hello!" for others |
| `ChatPartner`, `llm/chat/ChatPrompt` | Already by `Language.name` and the profile's variety | Unchanged; an empty variety for languages without one |
| `llm/Prompt` (check request) | `"Spanish (Peninsular)"` when no profiles were sent | Same for ca/es; any other language by name. Profiles are sent by ISO code for every language (verified with French) |
| Tone, gaps, memory, reply context, teach me, patterns prompts | Examples and one sentence written for Spanish and Catalan | Parameterized already; examples widened in `lab_reply_context_prompt.md`, `lab_teach_me_prompt.md`, `lab_patterns_prompt.md` |
| `core/corpus/WriterGaps` | English words in a fix are gaps the writer left | Not in English text (`isEnglishGap`) |
| `history/HistoryLanguages` | Any language already; Java's legacy `iw`/`in`/`ji` codes | Files under `he`/`id`/`yi` |
| `history/patterns/PatternRules` | Spanish/Catalan word lists applied to every language | Word rules only for es, ca or unknown; other languages get punctuation, capitals, accents, reason and spelling |
| `ui/phrasebook` | Phrase language read only as ca/es; Add phrase lists ca/es | Any language from the answer's English name; Add lists the writer's languages |
| `ui/appvoice` | Rule language ca/es | The writer's languages (+ the rule's) |
| `ui/words` | Import dialog languages = shown + bundled decks | + the writer's languages |
| `ui/conjugations`, Progress | Row for every language when scoped; "covers Spanish and Catalan" | Row only for ca/es; the page says "isn't available for French yet" |
| `core/morphology`, `core/vocab`, `data/vocabgaps`, `core/conjugations`, `core/chat/PracticeTargets`, `core/words/WordTokenizer` | Spanish and Catalan engines | Kept for es/ca (see degrade paths) |

## Decisions

- **A language is its ISO 639-1 code.** `Language.forCode` accepts the platform's ISO 639-1
  list (`LanguageCodes`), stored lowercase in current form (`iw` reads as `he`). Codes
  stored by History, chats, profiles and the scope choice ("ca", "es") stay valid as they
  are; nothing stored is rewritten.
- **Names come through a hook.** `core/language/LanguageNames` has `name(code, locale)` and
  `brand(code, locale)`; `Languages.names` is the platform's names (no brands) until the
  catalog installs its own at start-up. Prompts use the English name (`Language.name`).
- **The writer's languages** live in `lab_lang_generic` (`MyLanguagesStore`), owned by
  `Settings.myLanguages`. Until the writer edits the list nothing is stored and it reads as
  Catalan and Spanish: earlier builds always used exactly one Catalan and one Spanish
  profile, so that is "whatever exists" for every current writer. The list is never empty
  and holds at most 24 languages.
- **Profiles**: one per writer's language. Adding a language yields the default everyday
  profile (moderate punctuation, no variety, named in English); it is stored on its first
  edit, like the Catalan and Spanish defaults always were. Removing a language keeps its
  stored profile, which returns as it was when the language is added again. The check
  request sends every profile, sorted by code.
- **Data scopes by code.** Chat, History, Phrasebook and Time already filtered by
  code; for a language with no activity they show their usual empty states. Under Linguize,
  Progress shows a block per language with data (the writer's first, in their order), or the
  writer's languages when there is no data at all.
- **The selection menu keeps Catalanize and Castilianize.** An activity-alias's label is a
  static resource: Android can't relabel "first pinned language" at runtime, so two generic
  aliases would show the same fixed text whatever the writer chose. A general menu would need
  one alias per catalog language in the manifest, each enabled for the writer's languages;
  that belongs with the catalog (which owns the brand strings). For now: the two aliases work
  as before and, as declared in the manifest, are off by default; Settings offers a pinned
  entry only when its language is one of the writer's, or while it is on (so it can be
  turned off). "Linguize" (Auto) is always there.

## Degrade paths

| Engine | es / ca | Any other language |
|---|---|---|
| Lemmatizer | Morphology tables | Identity (each form is its own lexeme) |
| Words | Bundled 5,000-word decks, coverage | Used words without coverage; import a deck for the language |
| Your conjugations | Grid | Progress has no row; the page says it isn't available for that language yet |
| Your patterns | Word rules plus shared rules | Shared rules only (punctuation, capitals, accents, reason, spelling) |
| Chat practice targets | Rare tenses, deck words, register | None: the partner just chats |
| Chat opener without a key | Hand-written | Hand-written for en, fr, de, it, pt, nl, gl, eu; else "Hello!" |

## How it works

- `core/language/LanguageCodes.kt`: ISO 639-1 codes, normalization, English and display
  names, `fromEnglishName`; `LanguageNames`, `LocaleLanguageNames`, `Languages`.
- `core/language/MyLanguages.kt`: the list's pure rules (seed, read, write, add, remove, move).
- `data/language/MyLanguagesStore.kt`: the list in `lab_lang_generic`, with `changes()`.
- `ui/language/AppLanguage`: watches the store, so the selector follows any edit.
- `ui/components/LanguagePicker.rememberMyLanguages()`: the list for pickers, read off the
  main thread.

## Limits

- The selection menu has no entry for languages other than Catalan and Spanish (see above).
- Brand names and flags for other languages wait for the catalog; until then the selector
  shows the language's own name with a code badge.
- The word tokenizer splits on letters, so languages written without spaces (Chinese,
  Japanese, Thai) count whole runs as one "word" in Words.
- English-only gap detection assumes the writer's own language is English.
- Phrasebook sections and its text export list Catalan and Spanish first, then others.
