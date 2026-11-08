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
- **Your words page**: a language switch (when more than one language has words), four
  numbers (words, dictionary forms, words written, this month), "N new words this month",
  a chart of distinct words over time, then the word list with search and four orders:
  Most used, Newest, A–Z (Catalan or Spanish collation) and Not yet right (words only
  ever tried inside a fix). When the lemmatizer knows morphology, a Words / Dictionary
  forms switch groups "vaig", "anem" and "anar" under "anar". Names left out are listed
  under the list. Export CSV shares `word,lemma,count,tried,first,last` through the share
  sheet.
- **A word's page**: used right, tried, messages, first and last used, its dictionary
  form (or, for a dictionary form, the forms used), and the newest 30 sentences it appears
  in, with the word underlined (struck through in the proof colour where a fix replaced
  it). A sentence from a saved check opens that check.

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
words. Words in a corrected span count as **tried**, never as known; "words" and
"dictionary forms" count only forms used right at least once.

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
right use, distinct messages and newest 30 uses. Lemma groups come from `App.lemmatizer`
(Identity today, so each form is its own group and the switch stays hidden); a form with
two lemmas ("fuimos": ir, ser) counts toward both. Growth is the cumulative number of
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
