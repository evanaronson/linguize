# Your conjugations

Your conjugations shows which verb forms the writer actually uses, currently in Spanish and Catalan:
a grid of tenses by person, counted only from text they typed themselves. Empty cells are
forms they haven't used yet. Each cell opens the verbs and the writer's own sentences behind
it. Forms a fix replaced are listed apart as "tried, then fixed".

The same work gives the app its morphology: `App.lemmatizer` now turns word forms into
lexemes (lemma and part of speech) for Spanish and Catalan, which the word index and
vocabulary features use.

## The moment it serves

The writer has a minute, not a message to send. They wonder "do I ever use the
subjunctive?" or "have I stopped avoiding the past with vaig?". History and Your patterns
answer "what did I get wrong". This answers "what can I already do", from evidence: what
they wrote and kept. It serves the *slowly needing the check less* moment in the
[Lab README](README.md), offline and without a key.

## How to use it

- **Progress**: a "Your conjugations" row under Your writing, with how many forms you
  have used in the app's current language, which the page shows.
- **Language**: the app's, chosen in the top bar of the tabs; under Linguize, the Catalan
  or Spanish block's row opens it in that language ([Language scope](language-scope.md)).
  For a language other than Spanish and Catalan the page says what it covers.
- **The page, in order**: the language; the summary ("N of M" forms used, verbs); **Forms
  used each month**; **Tenses and persons** with its Other forms row; **Verbs you use**;
  **Tried, then fixed**; **How it's counted**.
- **Tenses and persons**: rows are tenses and moods, columns are persons (yo, tú, él/usted,
  nosotros, vosotros, ellos/ustedes; jo, tu, ell/vostè…). A filled cell shows how many
  times the form was used. An outlined empty cell is a form not used yet. Screen readers
  read each cell as "Present, First person singular: Used 3 times, 2 verbs".
- **Other forms**: one more row in the same card, under its own headers "Inf.", "Ger.",
  "Part.", with the same cells (each spans two person columns). Screen readers hear the
  full name: "Gerund: Used 3 times, 2 verbs". A cell opens the same cell page.
- **A cell's page**: uses, last used, the 5 verbs most used in that form with their uses
  there, "Show all N verbs", and up to 3 of the writer's own sentences.
- **Verbs you use**: the 5 most used verbs, each with how many forms and which tenses it
  was used in ("4 forms: Present, Preterite, Gerund"), then "Show all N verbs".
- **All verbs** (from "Show all"): every verb, or a cell's, with search (accent-insensitive)
  and a sort (Most used, Most forms, A–Z; a cell's list has Most used and A–Z).
- **A verb** opens its page in Words (the verb lexeme, "hablar/v"): counts, forms and
  sentences.
- **Forms used each month**: distinct tense and person cells used per month, last 6 months.
- **Tried, then fixed**: verb forms inside a span an accepted fix replaced.

### Decisions (owner feedback, Conjugations page)

- **Other forms is a table row, not a list.** Same cell component, same tap, same cell
  page as the grid; only its short headers differ. One pattern for every verb form.
- **The trend goes under the summary.** It's a summary over time, not detail. Words puts
  its chart right under its totals; Conjugations now does too, before the grid. Not inside
  "How it's counted", which explains numbers rather than showing them. Time spent still
  draws its weeks last; moving it under its totals would match (left to Time's owner).
- **Lists are capped.** Every verb list shows the 5 most used, then "Show all N verbs"
  opens a full page with search and a sort (`FilterDropdown`, `LinguizeTextField` and the
  lazy card rows Words uses). It scales to hundreds of verbs; the page itself stays short.
- **No duplication with Words.** Words already lists every verb as a lexeme with its uses,
  first and last use, forms and sentences. Conjugations shows only what Words can't: which
  forms and tenses a verb was used in, and a verb's uses within one cell (uses there are
  shared between readings, so they aren't Words' counts). The verb rows here no longer
  show total uses; a verb opens its Words page for those. Words needed no change: the link
  uses its existing `Screen.WordDetail(language, "lemma/v", "x")`. Considered and rejected:
  a "Verbs" filter in Words with tense info. It would add a second meaning to Words'
  filters and its rows, and the other agent is reworking them; the tenses belong with the
  grid.

## How it works

| Piece | Where | Layer |
|---|---|---|
| Features (tense, mood, person, number, analysis) | `core/morphology/Features.kt` | core |
| Spanish paradigms and lexicon | `core/morphology/Spanish.kt` | core |
| Catalan paradigms and lexicon | `core/morphology/Catalan.kt` | core |
| Adjectives, plural folding | `core/morphology/Nominal.kt` | core |
| Function words (articles, pronouns…) | `core/morphology/ClosedClass.kt` | core |
| Analyser, clitics, guessing, `MorphologyLemmatizer` | `core/morphology/Morphology.kt` | core |
| Tokenizer, compounds, counting, trend | `core/conjugations/ConjugationMap.kt` | core |
| Verb lists: rows, preview, search, sorts | `core/conjugations/VerbList.kt` | core |
| Screens, view model | `ui/conjugations/` | ui |
| Links (all verbs, a verb in Words) | `ui/home/ConjugationsLinks.kt` | ui |

Everything is pure Kotlin and offline. Nothing is stored: the grid is rebuilt from
`App.corpus` each time the screen opens, so deleting a check or chat removes its text from
the grid on the next visit.

`ConjugationsViewModel` builds Spanish and Catalan reports together from one corpus read.
A refresh replaces pending work instead of being dropped; an obsolete result or failure
cannot update the current request. Choosing a language while loading changes which of
those reports is shown, and completion preserves that selection. A failed read keeps
the last reports for retry; the corpus's existing source-failure fallback still applies.

### Morphology

**Generation, not lookup.** About 440 Spanish and 420 Catalan verbs are listed in the source
(the lists are short strings in `Spanish.kt` and `Catalan.kt`, not assets). Each is
conjugated once, on first use, into a form → readings table, with every simple tense and
mood (present, preterite or Catalan simple past, imperfect, future, conditional, present and
imperfect subjunctive, tú/vosotros imperative), infinitive, gerund, and participle in all
four genders and numbers.

- **Spanish rules**: -ar/-er/-ir endings; spelling changes (busqué, pagué, empecé, cojo,
  sigo, averigüé, venzo); stem changes marked in the lexicon (`:ie` pienso, `:ue` duermo,
  `:i` pido, `:í` envío, `:ú` continúo), with -ir verbs also changing in the preterite,
  gerund and subjunctive (durmió, sintiendo, pidamos); -cer/-cir with zc (conozco); -ducir
  preterites in j (traduje); -uir with y (construyo); vowel stems (leyó, leído).
- **Catalan rules**: -ar, -re, -er and -ir; -ir verbs are inchoative (serveixo) unless
  marked pure (`:p` dormo) or pure with o→u (`:u` surto); spelling before e/i (toquem,
  pagui, comenci, mengem); diaeresis (estudiï, conduïm); obro/obres/obre; rep from rebre.
- **Irregular verbs** are written out where they differ: Spanish ser, estar, ir, haber,
  tener, hacer, poder, decir, ver, dar, saber, querer, venir, poner, salir, traer, caer, oír,
  andar, caber, valer, reír; Catalan ser, estar, anar, haver, tenir, fer, poder, dir, veure,
  saber, voler, venir, viure, escriure, beure, conèixer, aparèixer, prendre and the -endre
  family, treure, creure, caure, riure, seure, moure, dur, córrer, néixer, créixer, deure,
  caldre, fugir. Many Catalan ones share one "velar" pattern (prenc, prengui, prengués).
  Prefixed verbs (obtener, proponer, aprendre, reconèixer) are conjugated as their base.
- **Pronouns attached**: Spanish enclitics are stripped (dímelo, diciéndome, hagámoslo,
  vámonos, sentaos, quedaros, mantenlo) and the host must be an infinitive, gerund or
  imperative; a subjunctive host with pronouns is an imperative (dígamelo is usted's
  imperative). Catalan hyphens and apostrophes are split: pronouns after the verb
  (dóna-m'ho, fer-ho) apply the same rule, elided pronouns before it (l'he, m'agrada) leave it
  as it is.
- **Unknown verbs**: if a word isn't in the table, regular endings of 4+ letters on a stem of
  3+ are guessed ("programaron" → programar) and marked `guessed`.
- **Nouns and adjectives**: about 150 common adjectives per language are inflected into the
  table (buenas → bueno, grogues → groc). Other words that look plural fold to a singular by
  rule (ciudades → ciudad, canciones → canción, cases → casa, cançons → cançó, pares → pare).
  Gender is folded only for listed adjectives, because "casa" is not a form of "caso".
- **Ambiguity is kept**: analyses are sets. "fui" is ir and ser; "cante" is present
  subjunctive first and third person.

### Lemmatizer (lexemes)

`MorphologyLemmatizer.lexemes(word, language)` returns every lexeme a form can be:

- Function words come from a closed-class table that follows the Spanish frequency deck's
  conventions: articles under "el" and "un", object pronouns under their subject form ("la"
  → el/art and ella/pron, "lo" → el/art and él/pron), possessives under the long form ("mis"
  → mío/det). Contractions are both parts ("del" → de/prep and el/art). Catalan follows the
  same shape ("els" → el/art and ell/pron).
- Verb forms are the infinitive with `Pos.Verb`; listed adjectives `Pos.Adjective`; folded
  plurals `Pos.Noun`; -mente adverbs (and Catalan -ment ones built on a feminine adjective)
  are their own lemma, `Pos.Adverb`.
- Known readings win over guesses. A guessed verb counts only as a finite form ("comando"
  is not taken as a gerund of "comar").
- Words that are verbs on paper but nearly always something else ("como", "para", "casa")
  drop the verb reading; common nouns that are also verb forms ("trabajo", "dona") keep both.
- A word nothing is known about is itself, `Pos.Unknown`.
- An optional `extraForms` table (form → lexemes per language) can be passed, for example
  a bundled frequency list; it is consulted only when the morphology knows the word by
  guessing or not at all.

### Counting rules (`ConjugationMap`)

- Only utterances whose language is the one shown are read. Text with no language is
  skipped.
- A word inside an `Utterance.corrected` span is not counted. It goes to "tried, then fixed".
- **Compound tenses**: a finite form of haber (Spanish) or haver (Catalan), optionally
  followed by one adverb (ya, siempre, ja, encara…), then a participle, counts once for the
  participle's verb, in the tense the auxiliary gives (present → perfect, imperfect or
  preterite → pluperfect, future → future perfect, conditional → conditional perfect, present
  subjunctive → perfect subjunctive, imperfect subjunctive → pluperfect subjunctive) and the
  auxiliary's person. Neither word is counted on its own.
- **Catalan periphrastic past**: vaig, vas/vares, va, vam/vàrem, vau/vàreu, van/varen
  followed by an infinitive (pronouns attached are fine: vaig dir-li) counts once as the past
  of the infinitive's verb. "Vaig a casa" is anar's present.
- If either word of a pair is corrected, the pair is tried, not counted.
- After an article, determiner or preposition ("el trabajo", "de paso") only an infinitive
  reading is kept. Spanish la/las/lo/los and Catalan el/la/els/les/l'/en are left out of this
  rule because they are also pronouns before verbs ("la tengo", "la vaig veure").
- **Ambiguity rule**: each use counts 1 in total, shared equally between its distinct
  readings (verb lexeme, tense, person). "cante" adds ½ to present subjunctive yo and ½ to
  él; "fui" adds 1 to preterite yo, ½ to ir and ½ to ser; "hablamos" adds ½ to present and
  ½ to preterite. A cell is used when anything was added. The cell page says when some uses
  counted in part. Counts show at most one decimal.
- Imperative: the grid's imperative row holds the tú and vosotros forms, and usted,
  nosotros and ustedes forms only when a pronoun is attached (dígamelo, hagámoslo). Without a
  pronoun, "cante usted" and "no cantes" are subjunctive forms and count there. There is no
  first person singular imperative.
- The grid is per verb lexeme: a use belongs to a verb (infinitive) in a cell.
- Trend: for each calendar month from the first to the last with a use, the number of
  distinct finite cells used that month and the total so far.

## Limits

- No dictionary of nouns: a form that is both a noun and a verb ("paso", "porta") counts as
  a verb unless an article or preposition comes before it.
- Verbs outside the lexicon are only guessed from long regular endings; irregular verbs
  that aren't listed are missed. Voseo (tenés) and the future subjunctive (hablare) are not
  generated.
- Spanish accents matter: "se" is a pronoun, not "sé". Text with missing accents may not be
  counted, or be read as another form.
- Words with no language in the corpus are skipped; the analyser doesn't detect language.
- The grid reflects only the corpus sources present in the build (checks and chats once
  their sources are added to `App.corpus`). It is not a test of knowledge: an unused form
  may be known, and a used one may have been copied.

## Tests

- `core/morphology/SpanishMorphologyTest`, `CatalanMorphologyTest`: regular paradigms,
  spelling and stem changes, irregular and prefixed verbs, enclitics, ambiguity,
  guessing, adjective and plural folding.
- `core/morphology/MorphologyLemmatizerTest`: lexemes, homographs, function words, -ment
  adverbs, guesses and the extra table.
- `core/morphology/VocabularyListCrossCheckTest` and `CatalanFormListCoverageTest`: compare
  with an external frequency list when one is present on the machine (skipped otherwise).
  Against the Spanish list, 99% of the listed forms of known verbs analyse to the right verb;
  the misses are voseo, the future subjunctive and misspellings.
- `core/conjugations/ConjugationMapTest`: counting, shares, corrected spans, compound tenses,
  the periphrastic past, nominal context, enclitic imperatives, language filter, examples,
  trend, grid shape, sentence cuts.
- `ui/conjugations/ConjugationLabelsTest`, `ui/home/ConjugationsNavigationTest` (also the
  verb list and the link into Words).
- `core/conjugations/VerbListTest`: the capped preview over 300 verbs, forms and tenses in
  grid order, a cell's verbs, search and sorts.
- `ui/conjugations/ConjugationPageTest`: the Other forms row, its headers, section order
  (trend under the summary).
