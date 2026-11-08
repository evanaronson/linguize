# Your words

Your words is a log of every word the writer has typed in each language, counted from
their own input only. It shows how many different words they use, how that number grew,
and, for each word, how often they used it right, how often they tried it and got it
fixed, when they first and last used it, and the sentences they used it in.

## The moment it serves

Not mid-message. The writer has a minute and wonders "how much Catalan do I actually
use?" or "which words do I keep reaching for and getting wrong?" History answers one
check at a time; Your patterns groups the fixes. Your words is the other half of the
evidence: what the writer already says on their own. It serves "slowly needing the check
less" from the [Lab README](README.md), and it is the first consumer of the shared
[writer corpus](corpus.md), so it lights up for every feature that adds a corpus source.

## How to use it

- **Home**: once a counted word exists, a "Your words" card appears under Your patterns:
  "312 words you've written in Catalan". It disappears if deletions leave nothing.
- **History**: a "Your words" row sits above the list.
- **Your words page**: a language switch (when more than one language has words), the
  headline numbers (words, plus lexemes and lemmas when morphology is known; words
  written, this month), "N new words this month", a chart of distinct words over time,
  then the word list with search and four orders: Most used, Newest, A–Z (Catalan or
  Spanish collation) and Not yet right (words only ever tried inside a fix). Names left
  out are listed under the list. Export CSV shares
  `word,lemma,lexeme_pos,count,tried,first,last` through the share sheet.
- **Three levels.** When the lemmatizer knows morphology (it returns any part of speech
  other than unknown), a "Word forms / Lexemes / Lemmas" switch appears:
  - *Word forms*: spellings as written ("vaig", "la"). An ambiguous form says what it
    could be: "could be el (art), ella (pron)".
  - *Lexemes*: a lemma with its part of speech, shown as "veure (verb)", with the forms
    used under it ("Forms: veig, veus"). Homographs stay apart: "la" counts toward
    "el (art)" and "ella (pron)".
  - *Lemmas*: every lexeme with the same dictionary form, with their parts of speech and
    forms ("art, pron. Forms: el, la").
  With `Lemmatizer.Identity` (every word unknown) the switch and the extra numbers stay
  hidden. Part-of-speech labels are short and localized (noun, verb, adj, adv, pron, det,
  art, prep, conj, num, interj, other).
- **A word's page**: used right, tried, messages, first and last used; for a form, the
  lexemes it could be; for a lexeme, its forms and which of them are ambiguous; for a
  lemma, its lexemes and forms. Then the newest 30 sentences it appears in, with the word
  underlined (struck through in the proof colour where a fix replaced it). A sentence
  from a saved check opens that check.

**The ambiguity rule.** A form that could be more than one lexeme counts toward every
candidate lexeme, with its full counts, and is marked ambiguous there (in the row's
"ambiguous:" list and on the lexeme's page). At the lemma level it counts once toward each
candidate lemma. So lexeme and lemma totals can add up to more than the words written;
the page says so under the switch, and "How it's counted" repeats it. Words written and
word-form counts are never inflated. In the CSV an ambiguous form has one row per
candidate lexeme, each with the form's counts.

## How it works

| Piece | Where | Layer |
|---|---|---|
| Tokenizer | `core/words/WordTokenizer.kt` | core (pure Kotlin) |
| Index, fingerprint, cache | `core/words/WordIndex.kt` | core (pure Kotlin) |
| Sorting, search, contexts, CSV | `core/words/WordViews.kt` | core (pure Kotlin) |
| The `check` corpus source | `history/corpus/CheckCorpusSource.kt` | history |
| Texts Linguize wrote (hashes only) | `data/lab/NotTypedTexts.kt` (`lab_words` preferences) | data |
| View model, screens, entries | `ui/words/` | ui |

**Only input counts.** The `check` source gives each visible saved check's *original*
text, exactly as typed, never the text that was put back. `Utterance.corrected` holds the
spans of fixes the writer accepted, plus fixes a rewording they accepted took over
(`retired`). Accepted rewordings are not marked: the original was right, only less
natural. Undone, ignored and copied fixes are not marked either: the writer kept their
words. Words in a corrected span count as **tried**, never as known; words, lexemes and
lemmas count only entries used right at least once.

**What is left out.**
- A check that reused another saved check's answer: the same text, counted once.
- A check whose text is exactly what an earlier check put back: Linguize's words.
- Text Linguize wrote and the writer then checked: a message with its gaps filled, or one
  said in another language. The selection menu and accessibility button record the
  16-character hash of such text (never the text) in `lab_words`, and the source skips
  saved checks with that `textHash`.
- Shared text can't be put back, so it never reaches visible History; Understand and
  Try it are never saved.

**Tokenizer.** Accents, ñ, ç and the Catalan l·l (also typed l.l, l•l, ŀl) stay inside a
word. Elisions split off (l'home is l' + home, d'aquí, m'agrada, n'hi), as do enclitics
(compra'l, digues-li, dóna-m'ho is dóna + m' + ho, anar-se'n). Other hyphenated words stay
whole (porta-avions). Numbers, words with digits, links, emails, @mentions, #hashtags,
emoji and laughter are skipped. Words are lowercased (NFC, one apostrophe) and keep their
character spans. A capital in mid-sentence, or after an elided article (L'Hospitalet),
marks a name. A capital at the start of a sentence or line counts as a word unless that
form only ever appears capitalized and at least once in mid-sentence. Shouted text says
nothing about names.

**Index.** Per language: each form's right and tried counts, first and last use, first
right use, distinct messages and newest 30 uses. Each form's lexemes come from
`App.lemmatizer.lexemes()`; lexeme entries (keyed `lemma/pos`, e.g. `el/art`) and lemma
entries gather the uses of their forms by the ambiguity rule above, so a verb form with
two lemmas ("fuimos": ir/v, ser/v) counts toward both. Growth is the cumulative number of
forms by the day each was first used right.

**Derived, never copied.** The index is rebuilt from `App.corpus` when the screen opens and
after every History write. `WordIndexCache` keys the last index by a fingerprint of every
utterance's id, language, time, text and corrected spans (and the month), so an unchanged
corpus isn't recounted and a deleted check leaves the counts at once. Nothing is stored
except the not-typed hashes; the CSV is written to the export cache folder the
FileProvider shares and replaced by the next one.

## Limits

- Only the newest 3,000 saved checks are read.
- Checks saved before this build that were of filled-gap or translated text can't be told
  apart from typed text and are counted.
- Name detection is a heuristic: a word capitalized for emphasis mid-sentence is left out,
  and a name only ever used at the start of a sentence is counted as a word.
- No spelling dictionary: a misspelling no fix caught counts as a word.
- A gap-filled text is left out whole, including the parts the writer typed.
- Contexts link to saved checks. Other sources (for example a `chat` source) show their
  name without a link until their screen can be opened by id.
