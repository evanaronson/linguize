# Frequency decks

The frequency lists that [Words](words.md) measures the writer's words against: the bundled
Spanish and Catalan lists, importing your own, and how a written word reaches a deck row.
The page itself, its list and filters are described in [Words](words.md). This page used to
be Vocabulary gaps, which is now part of Words.

## Bundled lists and attribution

The bundled lists are adapted from openly licensed frequency lists built from the
OpenSubtitles2018 corpus, and are shared under **CC BY-SA 4.0**:

- `app/src/main/assets/lab_vocab_es.csv` (`rank,lemma,pos,count,forms`): the 5,000 most
  frequent Spanish lemmas with part of speech and the word forms counted toward each.
  Adapted from `frequency.csv` in [doozan/spanish_data](https://github.com/doozan/spanish_data),
  which is derived from hermitdave/FrequencyWords with tagging by FreeLing.
- `app/src/main/assets/lab_vocab_ca.csv` (`rank,form,count`): the 5,000 most frequent Catalan
  **word forms**, from `content/2018/ca/ca_50k.txt` in
  [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords).
- `app/src/main/assets/lab_vocab_SOURCES.md` ships with them. Words shows the
  attribution under "About these lists".

Subtitles reflect spoken, informal language. The lists are a guide, not a syllabus. They
have no English glosses. Gloss is optional, and imported decks can bring their own.

## Importing a deck

The parser (`core/vocab/DeckParser.kt`) accepts:

- CSV (RFC 4180 quoting), semicolon-separated or tab-separated text. Tab wins if there is
  one. Otherwise the header line decides between comma and semicolon, because a field may
  itself list forms with semicolons.
- Anki's "Notes in plain text" export. `#separator:`, `#html:` and similar lines are
  skipped, and `#guid column:` / `#tags column:` columns are ignored. HTML is removed.
- An optional, flexible header. The word column can be called lemma, word, term, front,
  palabra, paraula, form… Rank (ascending) and frequency or count (descending) are both
  optional. Part of speech (pos, type…), gloss (gloss, meaning, translation, english,
  back…) and forms are optional too.
- No header. A mostly numeric column orders the rows, the first text column holds the words
  and the second one the gloss.

Words are lowercased, and leading articles ("el perro", "l'home"), bracketed notes and
feminine endings after a comma ("bueno, buena") are removed. A repeated lemma with the same
part of speech keeps its best rank, and ranks are renumbered 1..n. Files over 5 MB or
20,000 rows are refused or cut. UTF-8, UTF-16 with a byte order mark and Windows-1252 are
read.

## How it works

**Evidence.** Only the writer's own input counts: the word index built from `App.corpus`
(docs/lab/corpus.md). Uses inside `corrected` spans count as tried, never as used. Model
output never counts.

**Deck rows are lexemes.** A Spanish row is lemma plus part of speech from the CSV
(`Pos.of`). Catalan forms are grouped into lexemes by `App.lemmatizer`: counts are summed and
rows are ranked by the sum. Until real morphology lands (Identity), each Catalan form stays
its own row. The deck is marked `formsOnly` and Words says so ("This list counts word
forms…"), and a word then counts only toward its exact form.

**Matching one written word** (`DeckIndex.match`), in order. The first step that matches wins:

1. *Exact lexemes.* Lexemes from `App.lemmatizer.lexemes(word)` with a known part of speech
   count toward exactly that row. A Spanish form listed in the deck's `forms` column counts
   toward that row's exact lexeme ("tengo" → tener/v).
2. *By spelling.* When the lemmatizer only says `Pos.Unknown` (Identity), the lemma it gives
   (the word itself), plus a small table of very frequent irregular forms, counts toward
   every row with that lemma. These uses are flagged, and the page says "matched by
   spelling" (a count in the header, and on the word's page).
3. *Fallback folding* (`WordFolding`). Plural and gender ("casas" → casa, "buenas" → bueno,
   "cases" → casa), regular verb endings ("hablamos" → hablar, but only toward a verb row),
   and finally missing accents ("telefono" → teléfono, only when exactly one row fits).
   A candidate counts only if the deck has it. These matches are by spelling too.

**Ambiguity rule.** An ambiguous word counts toward every candidate lexeme it reaches at the
first matching step. "fue" counts toward ser and ir when both list it. "la" with a lemmatizer
that says {el/art, ella/pron} counts toward both. Each counted word adds one use to every row
it reaches.

With `App.lemmatizer`'s morphology, step 1 catches most words and Catalan forms group into
lexemes. Decks are read again when the lemmatizer changes.

**By lemma** is the same deck with rows that share a lemma merged (`VocabDeck.byLemma`).

**Names.** The word index leaves names out before matching (see [Words](words.md)).

**Coverage.** `DeckCoverage` (in `core/words`) runs each form of the word index through
`DeckIndex.match` once, sums the uses per row (uses inside corrected spans count as tried,
not used), finds multi-word rows in the writer's texts, and computes the milestones. It's
kept in memory under the word index fingerprint, the deck and the level; nothing is saved.
`VocabAnalyser` and `VocabReport` remain for Chat's practice targets.

**Storage** (Lab-local, `filesDir/lab_vocab_gaps/`): `decks/index.json` and one CSV per
imported deck, and `selection.json` (last language, deck and level). Reports saved by
earlier builds in `reports/` are no longer read.

## Code

- `core/vocab/`: `VocabDeck` (rows, lexeme or lemma level, grouping forms), `DeckParser`,
  `WordFolding`, `DeckIndex` (in `VocabAnalyser.kt`), `VocabSuggestions`.
- `data/vocabgaps/VocabGapsStore.kt`: imported decks, selection, and `BundledDecks`.
- `DeckLibrary.kt` (root): the decks for a language, read once per lemmatizer, import,
  delete and selection.
- `ui/words/WordDecks.kt`: the deck choice, coverage bars, Your decks and the import dialogs.

## Limits

- Without morphology, matching by spelling over-counts homographs. "como" counts for the
  conjunction and the adverb, and "vino" counts for the noun. Irregular verbs outside the
  deck's forms and the small fallback table are missed. Words says how many forms were
  matched by spelling.
- The Spanish list's lemmatization and tagging are FreeLing's ("madre" is listed under
  "padre"). Catalan is word forms until morphology arrives.
- Subtitle frequencies favour spoken language. "Rarer" means rare in subtitles, not
  sophisticated in any absolute sense.
- Multi-word entries from imported decks are found as phrases by exact spelling only.
