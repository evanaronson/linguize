# Vocabulary gaps

A frequency list of the language, from the top, with each entry marked as used or not yet
used in what the writer has typed. It shows the common words they already use, the ones
they don't use yet, and how that changes from month to month.

## The moment

Not the second before sending. This is for a quiet moment later, when the writer wonders
"what am I not saying?" Checks fix what was written. They can't show what the writer
avoids. A frequency list can: if "aunque", "quedar" or "tardar" never appear, the writer is
probably getting around them.

## How to use it

Home → **Vocabulary gaps** (below Your patterns).

- **Language and deck.** Spanish and Catalan lists are bundled. Imported decks show up beside them.
- **Count by.** By lexeme (the default) or by lemma. By lexeme, "como" the conjunction and
  "como" the verb count separately. By lemma, they count as one dictionary word. A deck with
  no parts of speech has only the lemma view.
- **Coverage.** One sentence, for example "You've used 62% of the 500 most common words",
  then bars for the top 100, 500, 1,000, 2,000 and 5,000 (each band only when the deck is
  larger, then the whole deck). It is a count of words used, not a level. There is no CEFR
  claim.
- **Over time.** One bar per month for the top 1,000 entries used so far, then the last three
  months: how many distinct lemmas were used that month, and what share were "rarer"
  (beyond rank 2,000, or not in the deck).
- **Words to try next.** The highest-ranked unused entries, grouped as verbs, nouns,
  adjectives, adverbs and other words. **Copy 5 words** puts a varied handful on the
  clipboard ("Words I want to use: …") to paste into a chat or a message. It doesn't depend
  on any chat feature.
- **Words.** The whole deck from the top. Filter: All, Not yet used, Used. Each used entry
  shows how many times and when it was last used. Tap one to see your own sentences with the
  word in bold.
- **Your decks.** **Import a deck** opens the system document picker
  (`ACTION_OPEN_DOCUMENT`, so no storage permission). You name the deck and choose its
  language. Imported decks can be deleted.

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
- `app/src/main/assets/lab_vocab_SOURCES.md` ships with them. The screen shows the
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

**Evidence.** Only the writer's own input counts: `App.corpus` utterances in the deck's
language, as typed (docs/lab/corpus.md). Words inside `corrected` spans (replaced by an
accepted fix) are skipped and reported as "skipped because a fix replaced it". Model output
never counts. Utterances with no known language are not counted.

**Deck rows are lexemes.** A Spanish row is lemma plus part of speech from the CSV
(`Pos.of`). Catalan forms are grouped into lexemes by `App.lemmatizer`: counts are summed and
rows are ranked by the sum. Until real morphology lands (Identity), each Catalan form stays
its own row. The deck is marked `formsOnly` and the screen says so ("This list counts word
forms…"), and a word then counts only toward its exact form.

**Matching one written word** (`DeckIndex.match`), in order. The first step that matches wins:

1. *Exact lexemes.* Lexemes from `App.lemmatizer.lexemes(word)` with a known part of speech
   count toward exactly that row. A Spanish form listed in the deck's `forms` column counts
   toward that row's exact lexeme ("tengo" → tener/v).
2. *By spelling.* When the lemmatizer only says `Pos.Unknown` (Identity), the lemma it gives
   (the word itself), plus a small table of very frequent irregular forms, counts toward
   every row with that lemma. These uses are flagged, and the screen says "matched by
   spelling" (a count under Coverage, and on the word's sentences).
3. *Fallback folding* (`WordFolding`). Plural and gender ("casas" → casa, "buenas" → bueno,
   "cases" → casa), regular verb endings ("hablamos" → hablar, but only toward a verb row),
   and finally missing accents ("telefono" → teléfono, only when exactly one row fits).
   A candidate counts only if the deck has it. These matches are by spelling too.

**Ambiguity rule.** An ambiguous word counts toward every candidate lexeme it reaches at the
first matching step. "fue" counts toward ser and ir when both list it. "la" with a lemmatizer
that says {el/art, ella/pron} counts toward both. Each counted word adds one use to every row
it reaches.

When `App.lemmatizer` gains Spanish and Catalan morphology, coverage improves without changes
here. Step 1 catches more words, Catalan forms group into lexemes, and the fingerprint (which
includes the lemmatizer's class) rebuilds every saved report.

**By lemma** is the same deck with rows that share a lemma merged (`VocabDeck.byLemma`).

**Names.** A capitalised word in the middle of a sentence that matches no row is treated as a
name and skipped. One-letter words that match no row are skipped as well.

**Report and cache.** `VocabAnalyser` builds a `VocabReport`: uses per row (count, last
used, the latest five places), milestones, distinct and rarer lemmas, and the monthly trend
(cumulative top-1,000 coverage, per-month distinct lemmas and rarer share, at most 24
months). It is saved in `filesDir/lab_vocab_gaps/reports/` with a SHA-256 fingerprint of the
analyser version, the lemmatizer, the deck (rows, forms, level) and every utterance in that
language. The corpus is read again every time the screen opens, and a saved report is used
only when the fingerprint matches. Deleting a check or a chat changes the fingerprint, so the
report is rebuilt and the numbers go down. Sentences are never stored: examples are
utterance ids and offsets, resolved against the live corpus.

**Storage** (Lab-local, `filesDir/lab_vocab_gaps/`): `decks/index.json` and one CSV per
imported deck, `reports/*.json` (derived and disposable), and `selection.json` (last
language, deck and level). Nothing else is read or written.

## Code

- `core/vocab/`: `VocabDeck` (rows, lexeme or lemma level, grouping forms), `DeckParser`,
  `WordFolding`, `VocabAnalyser` (+ `DeckIndex`), `VocabSuggestions`.
- `data/vocabgaps/VocabGapsStore.kt`: imported decks, report cache, selection, and
  `BundledDecks`.
- `VocabGapsLab.kt` (root): corpus + lemmatizer + store, cache by fingerprint.
- `ui/vocabgaps/`: screen, dialogs, view model, Home entry.
- Hooks: one line each in `HomeScreen`, `HomeActivity`, `Navigation`.

## Limits

- The only evidence is what the writer typed in features that add a `CorpusSource` to
  `App.corpus`. Until those sources are merged, the screen shows the deck with nothing used.
- Without morphology, matching by spelling over-counts homographs. "como" counts for the
  conjunction and the adverb, and "vino" counts for the noun. Irregular verbs outside the
  deck's forms and the small fallback table are missed. The screen says how many words were
  matched by spelling.
- The Spanish list's lemmatization and tagging are FreeLing's ("madre" is listed under
  "padre"). Catalan is word forms until morphology arrives.
- Subtitle frequencies favour spoken language. "Rarer" means rare in subtitles, not
  sophisticated in any absolute sense.
- Multi-word entries from imported decks are found as phrases by exact spelling only.
