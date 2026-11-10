# Language catalog

One canonical list of the languages Linguize can name, for the whole app: every ISO 639-1
language (183) with its English name, its name in itself, the product's brand verb in the
style of **Catalanize** and **Castilianize**, and a small circular flag or emblem. It is data
and a small API, and since lang-selector (docs/lab/lang-selector.md) it is where every
language label and icon in the app comes from.

## The moment it serves

The app is becoming language-agnostic. As soon as someone writes in Portuguese or Japanese,
the selector, Progress blocks and History rows need a name ("Portuguese", "português"), a
brand ("Portuguesize") and an icon that matches Catalan's senyera. Until now that existed only
for Catalan and Spanish (`ui/language/LanguageFlags.kt`); everything else fell back to a
square code badge and `Locale`'s display name.

## What's in it

`app/src/main/assets/lab_languages.json`, one entry per language:

| Field | Meaning |
|---|---|
| `code` | ISO 639-1 code (`ca`) |
| `english` | English name from CLDR (`Catalan`, `Bangla`, `Odia`) |
| `native` | The language's name in itself (`català`, `日本語`), in CLDR's casing: many languages write it in lower case |
| `brand` | The brand verb (`Catalanize`) |
| `flag` | A circle-flags key (`es-ct`), or `null` when no flag is fair |
| `emblem` | Only when `flag` is `null`: the key of the emblem Linguize draws instead (`nv`, `inuit`, …), see Emblems |
| `kind` | Omitted for living languages; `classical` (ae, cu, la, pi, sa) or `constructed` (eo, ia, ie, io, vo) |
| `macrolanguage` | `true` for ISO 639-3 macrolanguages (ar, zh, no, fa, …) |
| `macro` | The macrolanguage this one belongs to when that is listed too (nb, nn: `no`; tw: `ak`) |
| `aliases` | Other English names search should find (Farsi, Castilian, Flemish, Valencian) |
| `varieties` | Regional flags with the words that pick them out of a writing profile's variety (Spanish: Peninsular `es`, Mexican `mx`, Rioplatense `ar`, Colombian `co`; also English, Portuguese, French, German and Catalan) |
| `notes` | Why a brand or flag differs from the plain rule |

Flag keys are circle-flags file names, not language codes: `ca` is Canada's flag, Catalan's
key is `es-ct`.

**Nothing left out.** All 183 current ISO 639-1 codes are in. Classical and constructed
languages are marked with `kind`, not dropped: people learn Latin and Esperanto. Withdrawn
codes (`iw`, `in`, `ji`, `jw`, `mo`, `sh`, `bh`) are not entries, but `find` maps the first
five, and CLDR's `fil`, to today's code.

## Sources and licenses

- **Codes, English names, native names: Unicode CLDR 49** (npm `cldr-core` and
  `cldr-localenames-full` 49.0.0, Unicode License v3). The code list is every two-letter code
  CLDR's supplemental aliases pair with its ISO 639-2 code (181), plus `tl` and `tw`, which
  CLDR keeps as aliases but ISO 639-1 still lists. English names are CLDR's English display
  names; native names are each locale's name for itself. 140 come from CLDR. For the 43
  languages CLDR has no locale data for (Afar, Avestan, Latin, Navajo, …), the autonym is the
  common one in reference tables of ISO 639-1. Names are facts, not creative work.
- **Flags: HatScripts circle-flags** (MIT, LICENSE checked, commit 379588b), rasterised to
  96×96 lossless WebP.
- Both licenses ship in the APK as `assets/lab_languages_NOTICE.txt`, shown in Settings ›
  About › Open-source notices.

## Brand names

**The rule.** The English language name plus "-ize", as in Catalan + ize, Castilian + ize. All
183 are unique, capitalised and end in "ize".

1. **Keep the product's names**: Catalanize, Castilianize (Spanish is Castilianize, not
   Hispanicize).
2. **Prefer a real English word when it exists and its meaning is linguistic or cultural**:
   Anglicize (English), Gallicize (French), Germanize, Hellenize (Greek), Latinize, Italianize,
   Japanize, Sinicize (Chinese), Polonize (Polish), Hebraize, Gaelicize (Scottish Gaelic),
   Hibernicize (Irish), Cymricize (Welsh), Danicize (Danish), Fennicize (Finnish), Sanskritize,
   Yiddishize, Persianize, Swahilize.
3. **Never use a word whose main sense is a forced-assimilation policy.** Use a neutral form
   built from the language name instead, and say why in `notes`:
   - Hungarian is **Hungarianize**, not Magyarize.
   - Russian is **Russianize**, not Russify.
   - Arabic is **Arabicize** (adapt to Arabic, a linguistic term), not Arabize. Amharic is
     **Amharicize** for the same reason.
   - Turkish is **Turkicize**, not Turkify.
   - Bulgarian is **Bulgarianize**, not Bulgarize (the 1980s renaming campaign).
   - Norwegian is **Norskize**, not Norwegianize (fornorsking, the policy toward the Sami and
     Kven).
   - Vietnamese is **Vietnamesize** (Vietnamization was a war policy), Burmese **Burmesize**
     (not Burmanize), Pashto **Pashtize** (not Pashtunize), Sinhala **Sinhalaize** (not
     Sinhalize).
   - Somali is **Soomaalize**, from the autonym: "Somalization" is used for state collapse.
   - Romanian is **Romanianize**: Romanize means writing in Latin script.

   Germanize, Polonize and Sinicize are kept: the request named them, and their everyday
   sense is about language and culture, but each also has a policy history. Swap them for
   Germanize → Deutschize, Polonize → Polishize, Sinicize → Chinesize if that reads better
   later.
4. **Coined forms read well before they follow the rule exactly**:
   - Names ending in "-ish" take "-icize": Swedish → Swedicize, Kurdish → Kurdicize, Cornish →
     Cornicize, Luxembourgish → Luxembourgicize.
   - Names ending in "-ese" keep it: Portuguese → Portuguesize, Faroese → Faroesize, Maltese
     → Maltesize, Javanese → Javanesize (Japanese is the exception: Japanize).
   - A final vowel is usually dropped: Esperanto → Esperantize, Bangla → Banglize, Hindi →
     Hindize, Quechua → Quechuize, Zulu → Zulize.
   - Diacritics stay: Māorize, Bokmålize, Volapükize. Search ignores them.
5. **Ties are broken with another real name**: Interlingua is Interlinguize, so Interlingue
   is **Occidentalize** (its original name was Occidental). South Ndebele is Ndebelize, so
   North Ndebele is **Sindebelize** (its name in Zimbabwe). Kalaallisut is **Greenlandicize**.

## Flags

**The rule.**

1. Use circle-flags' own language flag (`flags/language/<code>.svg`) when there is one: 173 of
   183. That is the senyera for Catalan, Spain for Spanish, Portugal for Portuguese, the UK for
   English, the wiphala for Aymara, Esperanto's green star and a script badge for Arabic.
2. Else the country where the language is official or mostly written: Herero, Kuanyama and
   Ndonga → Namibia; Tsonga and Venda → South Africa; Twi → Ghana (as Akan); Wolof → Senegal;
   Walloon → Belgium; Limburgish → the Netherlands; Sanskrit → India; Sichuan Yi and Zhuang →
   China.
3. Else no flag (`null`) and an **emblem** (below): classical Avestan, Church Slavic and Pali;
   Fula, spoken across many states; and Cree, Inupiaq, Inuktitut, Navajo and Ojibwa, whose
   speakers a state flag would misrepresent.
4. One override: circle-flags draws Ossetian with South Ossetia's flag, a partially recognised
   state, so Ossetian has an emblem.

## Emblems

No language shows a code badge. The ten without a flag get a circular emblem Linguize draws
as a 24 dp vector drawable (`res/drawable/lab_emblem_<key>.xml`, a full coloured disc in the
same circle as the flags). The rule: the people's own flag where one flag stands for the
speakers; else a letter of the language's own script or a symbol of its culture. No
stereotypes: no bows and arrows, headdresses, feathers, tipis or "tribal" patterns. Letters
are drawn as paths, not text, so they never depend on a font being on the phone (Avestan,
Adlam and syllabics are missing from many).

| Code | Emblem | Why |
|---|---|---|
| nv | A simplified **Navajo Nation flag**: the tan field, the rainbow, the copper land and the four sacred mountains in their colours (black north, white east, turquoise south, yellow west) | The Navajo Nation's own flag, one nation, one flag |
| iu, ik (key `inuit`) | After the **Inuit Circumpolar Council flag**: a red-over-white sun on blue | The ICC flag stands for Inuit across Canada, Greenland, Alaska and Chukotka; Inupiat are Inuit. Shared, as Norwegian's three codes share a flag |
| cr | **ᐃ** (syllabic i), white on blue | Cree is many First Nations with many flags; picking one would misrepresent the rest. Syllabics are Cree's own script (ᐃᓂᓂᒧᐎᓐ, ininîmowin) |
| oj | **ᐊ** (syllabic a), white on green | Same reason; ᐊ is the first syllabic of ᐊᓂᔑᓈᐯᒧᐎᓐ, Anishinaabemowin |
| os | **Ӕ**, white on indigo | The letter of Ossetian's Cyrillic alphabet; not South Ossetia's state flag |
| cu | **Ѧ** (little yus), gold on crimson | A letter of early Cyrillic, the script of Church Slavonic |
| ae | A **flame**, amber on dark teal | The sacred fire of Zoroastrianism, whose scripture, the Avesta, is the Avestan language. Avestan's own letters weren't drawn: no reference outline was available to copy faithfully |
| pi | The **Dhamma wheel**, white on saffron | Pali is the language of the Theravada texts; it is written in many scripts, so no single letter fits |
| ff | **Ɓ** (hooked b), white on ochre | A letter of Fula's Latin alphabet (Pulaar, Fulfulde). Adlam, the script Fula speakers created, would be a fine alternative once a faithful outline is drawn |

The ICC flag is drawn from its description (a red and white sun on blue) and the Navajo flag is
simplified to four shapes; check both against official artwork before a release. Any code the
catalog doesn't know gets a speech bubble on a neutral circle (`lab_emblem_unknown`, tinted by
the theme), never letters.

Variety flags are data (they replaced `ui/language/LanguageFlags.kt`, now removed): Spanish Peninsular (Spain),
Mexican, Rioplatense or Argentinian (Argentina), Colombian; English British, American,
Australian, Canadian, Irish, New Zealand; Portuguese European and Brazilian; French, Canadian,
Belgian, Swiss; German, Austrian, Swiss; Catalan Central, Valencian and Balearic. A variety
that names none of them gets the language's flag.

**Size.** 168 distinct flags (languages share some, such as Norwegian's three), 96×96 px
lossless WebP in `res/drawable-nodpi/lab_flag_<key>.webp`: 509 KB in all, about 3 KB each.
96 px is a 24 dp icon at xxxhdpi. The JSON is 30 KB.

## API

- `core/language/LanguageCatalog` (pure Kotlin):
  - `LanguageCatalog.parse(json)`
  - `all()`: sorted by English name
  - `find(code)`: case-insensitive, accepts tags such as `pt-BR`, maps retired codes
  - `search(query)`: code, English, native, brand and aliases, ignoring case and accents.
    An exact code comes first, then names starting with the query, then names containing it.
  - `brandName(code)`
  - `flagKey(code, variety)`
  - `fold(text)`
- `CatalogLanguage`, `CatalogVariety`: the entries.
- `data/language/LanguageCatalogAsset.get(context)`: the app's one catalog, read from the
  asset once.
- `ui/language/catalog`:
  - `flagResource(catalog, code, variety)`: a drawable id or null
  - `flagDrawable(key)`: generated, key to drawable
  - `emblemDrawable(key)`, `emblemKeys`: the emblems (`LanguageEmblems.kt`, hand-written)
  - `languageIconContent(...)`: `Flag`, `Emblem` or `Unknown`
  - `@Composable LanguageIcon(code, variety, size = 24.dp)`: the circular flag or emblem with a
    hairline outline, or the speech bubble for an unknown code. Decorative: say the language's
    name beside it.

  The selector's `ui.language.LanguageIcon(option)` draws it for a language, and Linguize's
  circular "A" for Auto.
- `data/language/CatalogLanguageNames`: the catalog as `core/language/LanguageNames`
  (`Languages.names`), installed in `App.onCreate`: brand names, English names (in an English
  UI; the platform's localised name otherwise) and native names.

## Rebuilding

`scripts/build_language_catalog.py` writes the JSON (including each flagless language's
`emblem` key, from its `EMBLEM` table), the WebP flags and the generated
`LanguageFlagDrawables.kt` (the emblem drawables are hand-made and not generated) from extracted CLDR packages and a circle-flags clone. Brand names,
fallback autonyms, aliases, flag fallbacks and varieties are tables at its top. It needs
cairosvg and Pillow in a virtualenv. It is not part of the Gradle build. Its header says how
to run it with downloaded files kept apart.

## Limits

- English names are CLDR's, which sometimes differ from ISO's: Bangla (Bengali), Odia (Oriya)
  and Fula (Fulah). The ISO names are aliases, so search finds both.
- Native names follow CLDR casing ("español"). A menu may capitalise the first letter for
  its locale.
- Names are in English only. A localised UI should use CLDR's names for the user's language
  (`Locale.getDisplayLanguage`), with the brand verbs unchanged.
- A brand verb is a coinage, not a translation. Read the coined ones aloud before putting a
  language in front of its own speakers.
