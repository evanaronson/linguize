# Writer corpus, memory and morphology (Lab contracts)

Three small contracts let the conversation, memory and knowledge experiments be built
separately and still work together. They live in `core/corpus/Corpus.kt` and
`core/memory/UserMemory.kt`, and `App` exposes one instance of each.

## Corpus: only what the writer typed

The corpus is every piece of text the writer typed themselves, exactly as typed, before any
correction. It is the evidence for what they know.

- **Only input counts.** Model output never enters the corpus: not corrections, rewordings,
  replies, translations, glosses or suggestions. A word the writer only ever saw in a
  correction is not a word they know.
- **Wrong attempts don't count as knowledge.** `Utterance.corrected` marks the spans of the
  writer's text that an accepted fix replaced. Analysers skip words in those spans when
  counting what the writer knows (they may show them separately as "tried").
- **Derived, never copied.** `Corpus` asks each `CorpusSource` for its utterances on demand.
  Deleting a check or a conversation removes its input from every view built on the corpus.
  Analysers may cache results, but must rebuild when the corpus changes.
- **One source per feature.** Each feature that holds writer input implements
  `CorpusSource` over its own storage and adds one line to `App.corpus`. Ids are
  `"<source>:<its own id>"`.

Sources today: `check` (History: the original text of every saved check). Lab Chat
messages are History records (origin `chat`), so `check` covers them too: each message
as typed, with its accepted fixes as `corrected`; partner replies never enter. There is
no separate `chat` source, so nothing is counted twice. `Utterance.origin` carries
History's origin (`menu`, `button`, `chat`), so analyses can compare writing to people
with chat practice. A chat message is never skipped as a re-check of put-back text: it
is always newly typed.

## Word forms, lexemes and lemmas

Vocabulary is counted at three levels, and every feature uses the same terms:

- **Word form:** what was written ("fuimos", "la", "llama").
- **Lexeme:** a dictionary form with its part of speech (`Lexeme(lemma, pos)`): "ir"/verb,
  "el"/article, "ella"/pronoun, "llama"/noun, "llamar"/verb. This is the unit of vocabulary.
- **Lemma:** the dictionary form alone ("el", "ella", "llamar"), grouping lexemes that share it.

`App.lemmatizer` analyses a lowercase word form into the lexemes it can be
(`lexemes(word, language)`; `lemmas(...)` drops the part of speech). It starts as
`Lemmatizer.Identity` (the form is its own lemma, part of speech unknown); the conjugation
experiment supplies Spanish and Catalan morphology. Parts of speech use the shared `Pos`
tokens (n, v, adj, adv, pron, det, art, prep, conj, num, interj, other, unknown), the same as
the Spanish frequency deck. An ambiguous form returns every candidate; a feature that counts
it must state its rule (count toward each candidate, or split the count).

## User memory

`App.userMemory` is what Linguize remembers about the writer between conversations: who
they are, who they write to, what they're working on. It starts as `UserMemory.None`.

- Every fact names its sources (`"chat:<conversation id>"`); `forget(source)` removes what
  was learned only from it. Deleting a conversation calls `forget`.
- The writer can see, edit, delete and switch off everything remembered.
- Memory is about the person, not their mistakes; grammar evidence belongs to the corpus.
