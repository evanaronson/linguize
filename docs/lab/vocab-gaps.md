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
- `app/src/main/assets/lab_vocab_ca.csv` (`rank,form,count`): the 15,000 most frequent
  Catalan **word forms** (of 50,000), from `content/2018/ca/ca_50k.txt` in
  [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords). It ships three
  times as many forms as Words shows, because grouping forms into lexemes shrinks the list
  (see "Top 5,000" below).
- `scripts/build_vocab_decks.py` makes both files from the sources, deterministically:
  `python3 -I scripts/build_vocab_decks.py --spanish frequency.csv --catalan ca_50k.txt --out app/src/main/assets`.
  Its docstring lists the filters (Spanish: no DUPLICATE rows, proper nouns or lemmas
  that aren't lowercase Spanish letters; forms with a count of 50 or more, at most 40.
  Catalan: forms of Catalan letters only).
- The morphology also reads both whole lists (`BundledDecks.verbEvidence`) as evidence that
  a guessed verb exists, so "maneres" isn't read as a verb "manre"
  ([Your conjugations](conjugations.md#morphology)).
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
When every row has a count, merged rows are ranked by their summed count; otherwise each sits
where its first lexeme was.

**Top 5,000.** Each bundled deck shows exactly its 5,000 most frequent rows at the level
shown, lexeme or lemma (`BundledDecks.SIZE`), so the 5,000 bar is always full. The deck is
grouped first and cut after (`VocabDeck.top`); the rows below the cut stay in
`VocabDeck.reserve`, are never shown or counted, and only let `byLemma` merge the full list
before it cuts to 5,000 again. Earlier builds shipped only the top 5,000 Catalan forms, and
grouping them left 3,517 lexemes and 3,183 lemmas. Grouping 15,000 forms leaves about 11,400
lexemes and 10,000 lemmas, and their top 5,000 agree with grouping all 50,000 forms for about
99.5% of rows; the rest are near-ties at the cut, where a lexeme's rarer forms fall outside
the file. The Spanish source lists each lemma once (homographs are merged or flagged
DUPLICATE), so its 5,000 rows are 5,000 lexemes and 5,000 lemmas. Without morphology
(Identity) Catalan shows its top 5,000 forms.

**Names.** The word index leaves names out before matching (see [Words](words.md)).

**Coverage.** `DeckCoverage` (in `core/words`) runs each form of the word index through
`DeckIndex.match` once, sums the uses per row (uses inside corrected spans count as tried,
not used), finds multi-word rows in the writer's texts, and computes the milestones. It's
kept in memory under the word index fingerprint, the deck and the level; nothing is saved.
Chat's practice targets compute only used deck keys with their existing tokenizer
and matching rules. The former full report remains a test-only differential oracle.

**Storage** (Lab-local, `filesDir/lab_vocab_gaps/`): `decks/index.json` and one CSV per
imported deck, and `selection.json` (last language, deck and level). Reports saved by
earlier builds in `reports/` are no longer read.

File replacements report failure. An unreadable index or selection cannot be
overwritten as if it were a fresh install. Import publishes the CSV before committing
its index entry; a failed index write removes the unpublished CSV when possible.
Failed imports keep their dialog for retry. Deletion commits index membership first,
then removes the CSV and old report files; failed physical cleanup can be retried
while the screen remains alive. This is not a transaction across those files or a
durable orphan-cleanup queue.

## Code

- `core/vocab/`: `VocabDeck` (rows, lexeme or lemma level, grouping forms), `DeckParser`,
  `WordFolding`, `DeckIndex` and `usedDeckKeys` (in `DeckUsage.kt`), `VocabSuggestions`.
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
- Catalan sums count only the 15,000 shipped forms, so a lexeme near rank 5,000 whose
  other forms are rarer than that may rank a little low. Forms the morphology doesn't know
  stay rows of their own.
- Subtitle frequencies favour spoken language. "Rarer" means rare in subtitles, not
  sophisticated in any absolute sense.
- Multi-word entries from imported decks are found as phrases by exact spelling only.
