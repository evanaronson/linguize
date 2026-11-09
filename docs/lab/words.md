# Words

Words is one page per language that holds both halves of the writer's vocabulary: every
word of a frequency list, and every word the writer has typed. The words they know are a
subset of all the words, so it's one list. The page shows how much of the common words they
already use, how that grew, which common words they haven't used yet, and, for any word
they used, how often they used it right, how often a fix replaced it, and the sentences
they used it in.

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
app's current language, which the page shows. Switch it in the top bar
([Language scope](language-scope.md)).

- **Header.** For the chosen deck, a coverage sentence ("You've used 62% of the 500 most
  common words") and bars for the top 100, 500, 1,000, 2,000 and 5,000 (each only when the
  deck is larger, then the whole deck). It's a count of words used, not a level. Then the
  writer's totals: words, plus lexemes and lemmas when morphology is known, words written,
  this month, and "N new words this month".
- **Deck.** Spanish and Catalan lists are bundled. Imported decks appear beside them, and a
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
  With `Lemmatizer.Identity` (every word unknown) only word forms show.
- **The list.** One list over the deck and the writer's own words. Filters: **All**,
  **Used** (used right at least once), **Not yet** (deck words not yet used right) and
  **Tried** (replaced by a fix, never yet right), each with its count. Each row shows the
  deck rank, the word with its part of speech (short labels: noun, verb, adj…) and gloss
  when the deck has one, and "Used 4 times, Last Nov 3", "Tried once" or "Not yet". Words of
  the writer's that the deck doesn't have appear under All and Used with "not in list".
  Search (accent-insensitive) and four orders: Rank (the default with a deck; words not in
  the list come last), Most used, Newest and A–Z (Catalan or Spanish collation).
- **Words to try next** tops the Not yet filter: the highest-ranked unused verbs, nouns,
  adjectives, adverbs and other words. **Copy 5 words** puts a varied handful on the
  clipboard ("Words I want to use: …").
- **A word's page** (any word with a use): deck rank, part of speech and gloss; used right,
  tried, messages, first and last used; for a form, the lexemes it could be; for a lexeme,
  its forms and which are ambiguous; for a lemma, its lexemes and forms; for a deck row,
  the forms that reached it. Then the newest 30 sentences it appears in, the word underlined
  (struck through in the proof colour where a fix replaced it). A sentence from a saved
  check opens that check.
- **Under the list.** Names left out; **Export CSV** (`word,lemma,lexeme_pos,deck_rank,
  count,tried,first,last` through the share sheet); **Your decks** (import, delete); **How
  it's counted**; **About these lists** (the CC BY-SA attribution).

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

**Only input counts.** The `check` source gives each visible saved check's *original*
text, exactly as typed, never the text that was put back. `Utterance.corrected` holds the
spans of fixes the writer accepted, plus fixes a rewording they accepted took over
(`retired`). Accepted rewordings are not marked: the original was right, only less
natural. Undone, ignored and copied fixes are not marked either. Words in a corrected span
count as **tried**, never as used. Coverage counts a deck row as used only when some use
was outside a corrected span.

**What is left out.**
- A check that reused another saved check's answer: the same text, counted once.
- A check whose text is exactly what an earlier check put back: Linguize's words.
- Gaps the writer left for the check to fill ([Gaps in every check](gaps-in-check.md)):
  bracketed gaps are skipped, an accepted fill is a corrected span, and common English words
  in a corrected span are left out.
- Shared text can't be put back, so it never reaches visible History; Understand and
  checks on Write are never saved.
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
it reached, then the deck rows no form reached. Without a deck: just the writer's entries.

**Storage.** Nothing is copied from the corpus.
`filesDir/lab_vocab_gaps/` keeps imported decks and the last language and deck. The CSV is
written to the export cache folder the FileProvider shares and replaced by the next one.

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
