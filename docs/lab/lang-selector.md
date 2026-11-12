# Language selector and Languages page

The top bar's selector now speaks the language catalog: a short list of **Linguize** and the
writer's own languages, each with its brand name, circular flag and meaning, then **Add
languages…**, which opens one Languages page. Settings opens the same page.

## The user moment

Someone who writes in French as well as Catalan opens the selector, taps **Add languages…**,
types "fre", checks **Gallicize** and goes Back. Gallicize, with France's round flag and
"French" under it, is in the selector straight away, and in the card's picker, the writing
profiles and Progress. Later they find it again from **Settings › Writing preferences ›
Languages**: it is the same page.

## How to use it

- **Selector** (top bar of Chat, Notes, Progress, History): Linguize (Auto, "Auto-detect",
  the default), then the writer's languages in their order, each "Catalanize / Catalan" with a
  circular icon; a divider; **Add languages…**.
- **Languages page** (from the selector or Settings):
  - a search field: English name, the language's own name, brand name, alias or code
    (`LanguageCatalog.search`: "catala", "Gallicize", "日本", "pt");
  - **Your languages**, in the selector's order. Each one but the first has an up arrow ("Move
    Castilianize up") to reorder the short list;
  - **Suggested**, when History has languages the writer hasn't added (most used first);
  - **Every language**, the 183 catalog languages A to Z.
  Each row: icon, brand name, "English, own name" (copy style uses commas, not middle dots),
  and a checkbox. Checked means in the selector.
- **Settings › About › Open-source notices**: the catalog's licences (CLDR, circle-flags) and
  the word lists' attribution (CC BY-SA), as plain text.

## Decisions

- **A full page, not a sheet.** 183 rows, a search field and the keyboard need the whole
  height; a sheet would be half covered by the keyboard and fight its own drag with the list's
  scroll. A page also gets the app's normal Back (which closes the keyboard first,
  `KeyboardBackHandler` / `NavigationBackHandler`) and the top bar's "Back to …". It is one
  `Screen.Languages` drawn by one `LanguagesScreen`; both entry points navigate to it.
- **Changes apply at once**, like every Settings page: no Done button. The selector, pickers
  and Settings follow the list (`MyLanguagesStore.changes()` → `AppLanguage`), so the dropdown
  is right when the page closes.
- **Unchecking never deletes.** It only takes the language off the list; History, chats and
  its writing profile stay, and come back when it is checked again. The note at the top says
  so.
- **At least one language.** Unchecking the last one keeps it checked and says "Catalanize is
  your only language. Check another one first." (a toast, so it is seen wherever the list is
  scrolled, and read by screen readers). A full list (24) says so the same way.
- **Reordering** is "Move up" on Your languages: cheap, accessible (a labelled button, no drag
  gesture to discover), and enough for a short list. New languages go to the end.
- **Names come from the catalog everywhere.** `CatalogLanguageNames` is installed as
  `Languages.names` in `App.onCreate` (the catalog is read on first use), so the selector, the
  card's picker, Settings' writing profiles, Progress headings, History and chat rows show
  catalog names and brands. The card's picker (popups) says "Auto-detect" for detection, as
  copy style asks, and brand names ("Catalanize") with the same
  icons for Auto and the writer's languages.
- **Icons at 80 % opacity.** Every language icon (flags, emblems, the speech bubble and
  Linguize's "A") is drawn at `LanguageIconAlpha` (0.8) in `ui/language/catalog/LanguageIcon.kt`,
  never per call site, so the surface shows through and full-colour flags don't shout. The
  hairline outline stays opaque so pale flags keep an edge. 0.8 reads the same in both themes:
  0.75 turned whites muddy grey on the dark ground, 0.85 barely showed on the light one.
- **Circular icons everywhere.** The old rectangular flags (`ui/language/LanguageFlags.kt`
  and five drawables) and Linguize's rectangular "A" tile are gone; every icon is the
  catalog's circle, Castilianize's following the writer's Spanish variety. Languages without a
  flag have emblems, never code badges (docs/lab/language-catalog.md, Emblems).
- **Screen readers**: each row is one checkbox named "Gallicize, French, Français"; the
  selector says "Language: Catalanize, Catalan"; icons are decorative.

## How it works

- `ui/language/LanguageSwitch.kt`: the selector (`FilterDropdown` with a new optional
  `footer`), `LanguageIcon(option)`, `brandName`, `brandDetail`, `selectorOptions`.
- `ui/language/AddLanguages.kt`: pure sections (Yours, Suggested, Every, Results) and the
  check rule (Add, Remove, KeepLast, Full).
- `ui/language/LanguagesScreen.kt`: the page and Settings' `LanguagesSettingsRow`.
- `ui/language/AppLanguage.kt`: `move` and `varietyOf` added.
- `data/language/CatalogLanguageNames.kt`: the names provider; `LanguageNames.native` added.
- `ui/language/catalog/LanguageEmblems.kt`, `res/drawable/lab_emblem_*.xml`: emblems.
- `ui/settings/OpenSourceNotices.kt`: About and the notices page.
- `ui/home/Navigation.kt`, `HomeActivity.kt`, `ui/settings/SettingsScreen.kt`: two screens and
  three one-line hooks.

## Limits

- Names other than brands follow the UI language only through the platform's locale data; the
  catalog's own names are English.
- The ICC and Navajo Nation emblems are simplified drawings; check them against official
  artwork before a release.
- No drag to reorder; "Move up" one step at a time.
- The selection menu still has only Catalanize and Castilianize (docs/lab/language-generic.md).
