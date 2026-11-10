# Writer corpus, memory and morphology (Lab contracts)

Three small contracts let the conversation, memory and knowledge experiments be built
separately and still work together. They live in `core/corpus/Corpus.kt` and
`core/memory/UserMemory.kt`, and `App` exposes one instance of each.

## Corpus: only what the writer typed

The corpus represents the writer's original input, exactly as typed before correction.
The registered History source currently reads the newest 3,000 eligible saved checks;
it is a bounded evidence window, not a complete inventory of everything ever written.

- **Only input counts.** Model output never enters the corpus: not corrections, rewordings,
  replies, translations, glosses or suggestions. A word the writer only ever saw in a
  correction is not a word they know.
- **Wrong attempts don't count as knowledge.** `Utterance.corrected` marks the spans of the
  writer's text that an accepted fix replaced. Analysers skip words in those spans when
  counting what the writer knows (they may show them separately as "tried").
- **Derived from the owning source.** `Corpus` asks each `CorpusSource` for its utterances.
  There is no separate durable corpus table. The shared History reader caches a bounded
  snapshot in memory; deleting a check excludes it from subsequent successful reads.
  Deleting a Chat conversation queues its separate History cleanup, so that removal is
  not an atomic cross-store operation. Derived reports have their own refresh policies.
- **One source per feature.** Each feature that holds writer input implements
  `CorpusSource` over its own storage and adds one line to `App.corpus`. Ids are
  `"<source>:<its own id>"`.

Sources today: `check` (History: original text in its requested saved-check window). Lab Chat
messages are History records (origin `chat`), so `check` covers them too: each message
as typed, with its accepted fixes as `corrected`; partner replies never enter. There is
no separate `chat` source, so nothing is counted twice. `Utterance.origin` carries
History's origin (`menu`, `button`, `chat`), so analyses can compare writing to people
with chat practice. A chat message is never skipped as a re-check of put-back text: it
is always newly typed.

## Shared reads and explicit completeness

`App.historyEvidence` owns one `HistoryEvidenceReader` for the application's History
store. Patterns requests 400 checks, Practice 500, and `CheckCorpusSource` 3,000 for
Words, Conjugations and Chat targets. SQLite reads a window's sessions and suggestions
in one transaction. Its commit revision identifies that snapshot within the process;
it is neither a persisted data version nor an analyser fingerprint.

At a known unchanged revision, the reader reuses the largest successful window.
Smaller requests take the newest selected IDs from that window, preserving each
consumer's limit and the positions of records missing in a compatibility page/detail
read. They do not refill those positions with older checks. A revision change rejects
an overtaken read and permits one retry. Stores without a trustworthy revision are
read again rather than cached as current.

The cache stores full `SessionDetail` payloads: writer text, suggestions, attempts and
raw model answers. It is bounded by record count (at most 3,000 with today's consumers),
not by payload bytes. It remains in process memory across screens until replaced by
a larger requested window, invalidated by a History revision, or released with the
process. There is no time expiry; the application's revision watcher also evicts
obsolete snapshots when no feature is reading them. No database schema or migration
was added for this cache.

`Corpus.read(language)` returns utterances plus per-source status and coverage.
`Complete` means the requested window was read successfully, even when `hasMore`
reports older checks outside it. `Partial` and `Failed` preserve the distinction
between unavailable sources and successfully empty sources. Coverage includes the
requested limit, optional revision, and selected/loaded record counts before the
writer-input mapping. Reused checks or other excluded records can make the mapped
utterance list shorter without making the read fail.

Consumers that cannot use partial evidence call `Corpus.utterances()` (or
`requireComplete()`), which throws on `Partial` or `Failed`. Cancellation always
propagates and stops further source work. It is never recorded as a successful empty
read or swallowed as an ordinary unavailable source.

Chat's target cache uses the same revision boundary. A successful block stays fresh
for five minutes; a caller waits up to four seconds for a shared build. Timeout or
source failure can return the last successful block only if its known revision still
matches. Changed revisions revoke old blocks and in-flight publication; unknown
revisions force a reread and disable stale fallback. Failed or partial evidence, and
a failed configured bundled-deck load, do not become fresh cache entries. The next
request can retry. A timeout leaves the shared build running, while cancellation of
the source itself propagates. Target priority, morphology, vocabulary analysis and
the evidence window are unchanged.

History, Chat, Notes, saved phrases, memory and Practice schedules retain separate
ownership and retention policies. History-off Chat still stores its own messages but
adds no recorded checks to this corpus. A successful bounded read is not proof that
an absent source ID was deleted, and sharing its payload does not authorize one
feature to delete another feature's saved data.

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
