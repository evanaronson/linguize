# Words

Words is one page per language that holds both halves of the writer's vocabulary: every
word of a frequency list, and every word the writer has typed. The words they know are a
subset of all the words, so it's one list. The page shows how much of the common words they
already use, how that grew, which common words they haven't used yet, and, for any word
they used, how often they used it right, how often Linguize flagged it as wrong,
the share right, and the sentences they used it in.

It replaces two earlier pages, Your words and Vocabulary gaps. The deck and import details
are in [Frequency decks](vocab-gaps.md).

## The moment it serves

Not mid-message. The writer has a minute later and wonders "how much Catalan do I actually
use?", "what am I not saying?" or "which words do I keep getting wrong?" History answers
one check at a time. Your patterns groups the fixes. Words is the rest of the evidence: what
the writer already says on their own, and what they get around. A frequency list can show
avoidance that no check can: if "aunque" or "quedar" never appears, the writer is probably
avoiding it. It serves "slowly needing the check less" from the [Lab README](README.md), and
it reads the shared [writer corpus](corpus.md).

## How to use it

Progress → **Words** (under Your writing): "312 words you've written in Catalan" for the
app's current language, which the page shows. Choose it with the selector in the top bar
of Progress; under Linguize each language in use has its own Words row
([Language scope](language-scope.md)).

- **Header.** For the chosen deck, a coverage sentence ("You've used 62% of the 500 most
  common words") and bars for the top 100, 500, 1,000, 2,000 and 5,000 (each only when the
  deck is larger, then the whole deck). It's a count of words used, not a level. Then the
  writer's totals: words, plus lexemes and lemmas when morphology is known, words written,
  this month, and "N new words this month".
- **Deck.** Spanish and Catalan lists are bundled, each the top 5,000 at the level shown
  ([Frequency decks](vocab-gaps.md)). Imported decks appear beside them, and a
  dropdown ("Deck: … ▾") shows when there is more than one.
- **Words over time.** One chart with two lines on one axis: all the writer's distinct
  words at the level shown, and how many of the deck's top 1,000 (or the whole deck, if
  smaller) they had used by then. The second line is a subset of the first.
- **Show, Sort, Count by.** Three dropdowns above the list ("Show: Used (40) ▾", "Sort:
  Most used ▾"); filters are dropdowns, never buttons. When the lemmatizer knows
  morphology, "Count by" chooses Word forms, Lexemes or Lemmas:
  - *Lexemes* (the default): a lemma with its part of speech, so "la" the article and
    "la" the pronoun stay apart. Deck rows are lexemes too.
  - *Lemmas*: every lexeme with the same dictionary form. The deck is viewed by lemma.
  - *Word forms*: spellings as written ("vaig", "la"). Each form shows the best deck rank
    it reached. Deck rows no form reached appear as Not yet.
  With `Lemmatizer.Identity` (every word unknown) only word forms show. A word the
  morphology only guesses is a verb lexeme only when the bundled frequency list attests the
  verb ([Your conjugations](conjugations.md#morphology)), so a plural such as "maneres" is
  one lexeme (manera/n), not also a made-up verb: lexeme counts and the Catalan deck's
  grouped rows drop those false verbs.
- **The list.** One list over the deck and the writer's own words. Filters: **All**,
  **Used** (used right at least once), **Not yet** (deck words not yet used right) and
  **Used wrong** (Linguize flagged it at least once), each with its count. Each row shows the
  deck rank, the word with its part of speech (short labels: noun, verb, adj…) and gloss
  when the deck has one, and its use: "12 right, 3 wrong" over "80% right, last Nov 3", or
  "Not yet". Words of the writer's that the deck doesn't have appear under All and Used with
  "not in list". Search (accent-insensitive) and five orders: Rank (the default with a deck;
  words not in the list come last), Most used, **Most misused**, Newest and A–Z (Catalan or
  Spanish collation).
- **Words to try next** tops the Not yet filter: the highest-ranked unused verbs, nouns,
  adjectives, adverbs and other words. **Copy 5 words** puts a varied handful on the
  clipboard ("Words I want to use: …").
- **A word's page** (any word with a use): deck rank, part of speech and gloss; Used right,
  Used wrong, Right (the share right, "80%"), messages, first and last used; for a form, the lexemes it could be; for a lexeme,
  its forms and which are ambiguous; for a lemma, its lexemes and forms; for a deck row,
  the forms that reached it. Then the newest 30 sentences it appears in, the word underlined
  (struck through in the proof colour where Linguize flagged it). A sentence from a saved
  check opens that check.
- **Under the list.** Names left out; **Export CSV** (`word,lemma,lexeme_pos,deck_rank,
  right,wrong,accuracy,first,last` through the share sheet); **Your decks** (import, delete); **How
  it's counted**; **About these lists** (the CC BY-SA attribution).

**Right, wrong and the share right.** Every use of a word is either right (outside every
corrected span) or wrong (inside one: Linguize proposed fixing it), never both.
Accepting, ignoring, copying or undoing the fix does not change that judgment.
So right and wrong add up to the uses, and either can be the larger: a word used 15 times
and flagged 3 times is "12 right, 3 wrong", 80% right. The share is
`WordAccuracy.percent(right, wrong)`: right over all uses, rounded, never 100% while any use
was wrong and never 0% while any was right; with no uses there is no percent (a deck row
not reached shows "Not yet"). The page used to say "Used right 12" and "Tried 3", which read
as 12 right out of 3 attempts; "Tried" was always the wrong uses. In code the wrong count is
still `WordCounts.tried` (and `WordUse.tried`), with `wrong` and `accuracy` beside it, so the
APIs other features read are unchanged.

**Most misused** orders by the number of wrong uses, most first, then by the lower share
right, then by the most recent use. The count comes first on purpose: a word flagged 5 times
out of 20 is a bigger habit than one fixed once out of once, even at 75% against 0%. Words
never used wrong follow, most recently used first. **Used wrong** shows the same words: any
with at least one wrong use (it used to show only words never yet right; its token is still
`tried`).

**The ambiguity rule.** A form that could be more than one lexeme counts toward every
candidate lexeme, with its full counts, and is marked ambiguous ("ambiguous: la" on the
row and on the lexeme's page). At the lemma level it counts once toward each candidate
lemma. A form that reaches several deck rows counts toward each ("fue" toward ser and ir).
So lexeme, lemma and deck totals can add up to more than the words written. The page says
so under the list's controls, and "How it's counted" repeats it. Words written and word-form counts
are never inflated. In the CSV an ambiguous form has one row per candidate lexeme, each with
the form's counts.

## How it works

| Piece | Where | Layer |
|---|---|---|
| Tokenizer | `core/words/WordTokenizer.kt` | core (pure Kotlin) |
| Word index, fingerprint, cache | `core/words/WordIndex.kt` | core (pure Kotlin) |
| A deck against the index | `core/words/DeckCoverage.kt` | core (pure Kotlin) |
| The one list: rows, filters, sorts, chart | `core/words/WordList.kt` | core (pure Kotlin) |
| Contexts, CSV | `core/words/WordViews.kt` | core (pure Kotlin) |
| The share right | `core/words/WordAccuracy.kt` | core (pure Kotlin) |
| Decks, parsing, matching rules, suggestions | `core/vocab/` ([Frequency decks](vocab-gaps.md)) | core |
| The `check` corpus source | `history/corpus/CheckCorpusSource.kt` | history |
| Bundled and imported decks, selection | `data/vocabgaps/VocabGapsStore.kt`, `DeckLibrary.kt` (root) | data, root |
| View model, screens, deck section | `ui/words/` | ui |

**One pass.** The corpus is read once into the word index. `WordIndexCache` keeps the last
index under a fingerprint of every utterance's id, language, time, text and corrected spans
(and the month), so an unchanged corpus isn't recounted, and a deleted check or chat leaves
the counts at once. The chosen deck is measured against that index, not against the corpus
again: each written form goes through the deck's matching rules once (`DeckIndex.match`:
lexemes with a known part of speech, then the deck's own forms column, then spelling and
folding), and multi-word rows ("a menudo") are found in the writer's texts. The coverage is
kept under the index fingerprint, deck and level. Reports are no longer saved to disk.

**Request ownership.** Each query captures evidence, language, deck and counting level
as one result. Scope changes and observed History revisions invalidate older work before
the rebuild debounce; late coverage cannot attach to a newer selection. Export waits for
the complete current result. Selection writes check the same ownership under the store
lock. Deck import and deletion run separately from replaceable queries: a finished import
selects its deck only if the writer has not chosen another scope, and failed deletions keep
their exact cleanup IDs for Retry. These mutations and retry IDs belong to the view model;
they are not a persistent queue and do not resume after process death.

**Only input counts.** The `check` source gives each visible saved check's *original*
text, exactly as typed, never the text that was put back. `Utterance.corrected` holds the
words flagged by fixes from the ending check, including ignored, undone, copied and retired
fixes. Superseded checks do not override a later judgment. Ordinary rewordings are not
marked: the original was right, only less natural. Only words a fix proposes changing are
marked: a word-level comparison of the fix's source and its replacement leaves out the words
it kept as written, so "plou" to "plou," (a comma) or "vaig platja" to "vaig a la platja" (a
missing word) marks nothing, "el home" to "l'home" marks "el", and a change of accent or case
marks the word. Words in a marked span count as **used wrong**, never as used right. Coverage counts a deck row as used only when some use
was outside a corrected span.

**What is left out.**
- A check that reused another saved check's answer: the same text, counted once.
- A check whose text is exactly what an earlier check put back: Linguize's words.
- Gaps the writer left for the check to fill ([Gaps in every check](gaps-in-check.md)):
  bracketed gaps are skipped, a proposed fill is a corrected span, and common English words
  in a corrected span are left out.
- Shared text can't be put back, so it never reaches visible History; Understand and
  checks on Notes are never saved.
- Names: a capital in mid-sentence, or after an elided article (L'Hospitalet). A capital at
  the start of a sentence counts as a word unless that form only ever appears capitalized.

**Tokenizer.** Accents, ñ, ç and the Catalan l·l (also typed l.l, l•l, ŀl) stay inside a
word. Elisions split off (l'home is l' + home, d'aquí, m'agrada, n'hi), as do enclitics
(compra'l, digues-li, dóna-m'ho is dóna + m' + ho, anar-se'n). Other hyphenated words stay
whole (porta-avions). Numbers, words with digits, links, emails, @mentions, #hashtags,
emoji and laughter are skipped. Words are lowercased (NFC, one apostrophe) and keep their
character spans.

**The one list** (`WordList.rows`). At the lexeme and lemma levels: every deck row with the
writer's use (or none), then the writer's entries none of whose forms reached the deck
("not in list"). At the form level: every form the writer typed, ranked by the best deck row
it reached, then the deck rows no form reached: unused ones, and phrases ("a menudo") found
only in the texts, which used to drop out of this level. Without a deck: just the writer's entries.

**Storage.** Nothing is copied from the corpus.
`filesDir/lab_vocab_gaps/` keeps imported decks and the last language and deck. The CSV is
written through the application's shared `Exports` owner to the cache folder the
FileProvider shares. Each export gets a unique file: a new Words CSV replaces older Words
files while keeping the current History export, and a new History export retires both.
Clear and deletion cleanup also retire both. A captured retirement generation rejects
stale evidence and queued writers; a late callback can discard only its own exact file.
The existing five-minute expiry policy applies to both formats.

## Limits

- Only the newest 3,000 saved checks are read.
- Checks saved before the not-typed hashes existed, of translated text, can't
  be told apart from typed text and are counted.
- Name detection is a heuristic: a word capitalized for emphasis mid-sentence is left out,
  and a name only ever used at the start of a sentence is counted as a word.
- No spelling dictionary: a misspelling no fix caught counts as a word.
- Matching by spelling (when no part of speech is known) over-counts homographs. The header
  says how many forms were matched that way.
- Subtitle frequencies favour spoken language. The lists are a guide, not a syllabus.
- The chart counts a word on the day it was first used right. It doesn't show words that
  fell out of use.
- Contexts link to saved checks. Chat sentences show "Conversation" without a link.
