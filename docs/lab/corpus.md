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

Sources today: `check` (History: original text of applied checks) and `chat`
(conversations: each message as typed, before pre-send corrections).

## Lemmatizer

`App.lemmatizer` maps a lowercase word to its dictionary forms in a language
("fuimos" to {"ir", "ser"}). It starts as `Lemmatizer.Identity`; the conjugation experiment
supplies Spanish and Catalan morphology. Vocabulary features use it so that "vaig", "anem"
and "anar" all count toward "anar".

## User memory

`App.userMemory` is what Linguize remembers about the writer between conversations: who
they are, who they write to, what they're working on. It starts as `UserMemory.None`.

- Every fact names its sources (`"chat:<conversation id>"`); `forget(source)` removes what
  was learned only from it. Deleting a conversation calls `forget`.
- The writer can see, edit, delete and switch off everything remembered.
- Memory is about the person, not their mistakes; grammar evidence belongs to the corpus.
