# Bonanza behavior contracts

These tests establish a baseline before cleaning up `feature/bonanza`, starting at
`d5d92cc13f083411ff60735c59a743ac20780704`. They preserve successful behavior and
make confirmed failures reproducible. Step 3 repairs persistence acknowledgements
and memory command invalidation; step 4 gives asynchronous work explicit ownership;
step 5 reconstructs scoped routes and Practice runs; step 6 shares bounded History
evidence and makes read failure explicit. Their repaired contracts now run in the
normal suite.

The Notes fixtures follow the later Notes navigation change (`16c25a7`, merged in
`ef5150e`): loading the list does not open an editor; a note is opened explicitly.
Step 4 repairs the pending-open defect: input from the available editor belongs to
the requested note and survives the delayed read.

## Run the two suites

The normal regression suite includes the new preservation tests:

```sh
./gradlew testDebugUnitTest lintRelease
```

Desired behavior that the baseline violates is marked with JUnit's
`@Category(KnownBonanzaDefect::class)`. Run only those contracts explicitly:

```sh
./gradlew testDebugUnitTest -PbonanzaKnownDefects
```

**The second command is expected to exit nonzero while the listed defects remain.** Its failures
are assertions of the desired behavior, not assertions that bugs should persist.
It excludes normal tests; normal runs exclude this category. No test uses `@Ignore`
to hide a known defect. When fixing a defect, remove its category and keep the test
in the normal suite. A known-contract test that unexpectedly passes needs review
and promotion, not another assertion that recreates the bug.

The opt-in run writes separate reports so it does not overwrite the normal run:

- Normal: `app/build/reports/tests/testDebugUnitTest/index.html`
- Known defects: `app/build/reports/tests/testDebugUnitTest-known-defects/index.html`
- XML: corresponding directories under `app/build/test-results/`

Both suites run offline, without provider credentials. Android unit tests need
JDK 21 and the Android SDK. The existing optional corpus/fuzz checks can also be
enabled with shipped files:

```sh
LAB_VOCAB_ES="$PWD/app/src/main/assets/lab_vocab_es.csv" \
LAB_VOCAB_CA="$PWD/app/src/main/assets/lab_vocab_ca.csv" \
LINGUIZE_FUZZ_REPLIES="$PWD/eval/fuzz/baseline/replies.jsonl" \
LINGUIZE_FUZZ_PARSER=legacy \
./gradlew testDebugUnitTest
```

## Behavior to preserve

| Test class | Protected behavior |
|---|---|
| `ChatBehaviorContractTest` | Clean checks send automatically; History-off chat still stores raw/sent text and the accepted subset; failed/unclear checks keep the draft editable; existing manual titles survive replies; bundled translations need no extra request. |
| `LabUserMemoryConcurrencyTest` | Writer edits survive a stale learning proposal; queued learning sees the previous committed answer; cancellation preserves facts and releases the learning queue. |
| `NotesAsyncContractTest` | Explicit opening waits for the initial list; editing after opening saves the selected note and keeps the others; switching notes saves outgoing edits and restores the selected note's language/title. |
| `PatternsAsyncContractTest` | Delayed explanations retain their evidence scope; provider and History failures preserve the last result and allow retry. |
| `WriterEvidenceContractTest` | Real word, vocabulary, and conjugation consumers preserve accepted-span counts, original examples/offsets and language isolation; rebuilding after deletion removes unique source evidence. |
| `ComposedCheckContractTest` | The actual base + gaps + tone request has fixed system/schema fingerprints, payload, options and routing for each provider; reply repair retains captured context; fresh and legacy parsing stay distinct. |
| `PersistenceBoundaryTest` | Draft write failure preserves cache and disk and permits retry; imported decks and selection round-trip; Practice returns the schedule it saved. |

The composed-request fingerprints were independently captured from the reviewed
assets. Expected values are not computed by the composition functions under test.
Review prompt-policy changes before updating these fixtures. These checks do not
measure a live model's judgment or replace the model evaluation suite.

## Persistence repairs (step 3)

Eight original failure contracts are now normal regressions: rejected Chat writer
saves; Memory clear, queued source deletion and replacement provenance; failed
Memory deletion; and failed Phrasebook, Practice and vocabulary-selection writes.
Additional tests exercise retry, reopening, failed reads, Undo, disabled learning,
and interrupted conversation cleanup.

The file stores use a small shared replacement primitive and publish committed
state after success. Their screens retain input or the failed command for retry.
Chat waits for the message write before finishing the check or requesting a reply;
its deletion cleanup is persisted with the local deletion. Memory checks a command
generation before requesting learning and again when applying it, so old work
cannot reverse Clear, deletion or an off/on cycle. Learning acknowledges only a
successfully processed exchange. Reworded facts cite the source of the replacement
text; identical confirmations can retain multiple supporting sources.

These are deliberate corrections to failure behavior. Successful request contents,
saved text and accepted changes, file formats, independent retention rules, and the
500-check Practice window remain unchanged. Chat adds a forward SQLite migration
for pending cleanup; it does not migrate or combine the other feature stores.

## Asynchronous ownership repairs (step 4)

Six more original contracts now run in the normal suite: pending Chat creation,
late creation replacing a newer selection, stale reply status, automatic titles
overwriting manual titles, typing during a Notes open, and a Patterns explanation
publishing under the wrong language.

This step also adds 76 regressions for delayed completion, overlapping commands,
cross-editor saves, retry ownership and export retirement. The normal suite has
1,880 passing tests with the optional corpus/fuzz fixtures enabled; no earlier
test was removed. Release lint and assembly pass, with the existing 66 lint
warnings and one hint.

Chat owns selection and partner requests separately. Pending creation is tracked
by conversation ID; message appends check ownership and return an authoritative
thread snapshot, and automatic titles update only an eligible stored conversation.
Accepted writer saves still finish when navigation supersedes their screen.

Notes gives each requested editor its own input and serializes accepted commands.
Shared store ownership protects both note content and the persisted selection;
delayed saves cannot recreate deleted notes or overwrite a newer edit. Input can
arrive before the saved note finishes loading. Accepted commands drain after the
editor closes, while disposed or superseded editors cannot resolve navigation.

Words publishes a coherent evidence/language/deck/level result only for its current
request. Patterns, Time and Conjugations also reject superseded work. Patterns
protects saved explanations at its storage boundary, including explicit deletion
and reconciliation with History. Words exports now share the application's export
owner: captured retirement generations reject stale evidence and queued writers,
and exact-file checks protect newer exports from old cleanup callbacks.

These changes preserve the existing algorithms, evidence windows, file formats,
normal export lifetimes and consecutive Chat sends. They do not implement route or
process reconstruction, or change the corpus's missing/failure policy.

## Route and run reconstruction (step 5)

Progress drilldowns save their language scope with the destination. Initial
restoration preserves an Auto language block; an actual app-language change clears
block overrides along the live path. Word and conjugation details retain their
intrinsic language and item identity. Legacy route tokens remain readable. The
visible screen applies its scope, replacing the activity's feature-wide broadcasts.

Delayed Chat/Notes navigation callbacks belong to the exact path that requested
them. A missing conversation cannot pop a newer page, including a later visit to
the same destination. A startup note acquires its explicit route ID after its first
successful save, so another editor's current-note selection cannot change what that
route restores. Canonicalizing that current-note route retains the editor's content
key, preserving focus and field state while the writer is using it.

Practice saves one versioned checkpoint of run and page-entry identities, item IDs,
position, small answer/repeat decisions and schedule baselines. Items are rebuilt
from the existing History source. There is no separately restored “already started”
flag, and no full item/report or sentence text in the run checkpoint. Durable
scheduling remains authoritative: restoration reconciles an older checkpoint before
showing the run, and replay of a pending grade cannot apply the same update twice.
An ambiguous newer durable schedule supersedes the stale restored item; the
checkpoint is not a durable transaction log. Fresh overlapping live runs retain
the existing serialized grading behavior.

These changes retain the scheduling algorithm, first-answer grading, one repeat
after a miss, and the documented 500-check window. They add no storage migration.

The step adds 44 regressions without removing earlier tests. Coverage includes
actual Home activity rotation and saved-Bundle recreation, fresh feature models,
Practice saved-state registry restoration and durable grade conflicts, and Compose
content identity during route changes. The final normal run passes all 1,924 tests
with zero skips and the optional corpus/fuzz fixtures enabled. Release lint and
assembly pass; lint remains at the existing 66 warnings and one hint.

## Shared evidence reads (step 6)

One application-owned `HistoryEvidenceReader` shares the largest successful window
for a store's current process revision. SQLite selects the eligible sessions and
their suggestions in two queries in one transaction, using the existing row
decoders, ordering and visibility rules. Writes and evidence reads share a lock;
the revision advances after commit inside IO, so cancellation while returning to
the caller cannot conceal a committed deletion. No database migration is needed.

Patterns still selects 400 checks across languages before filtering. Practice still
selects 500, retains its preferred item identities and applies its existing pruning
policy only after a successful read. Corpus consumers still select 3,000 before
the existing reuse, original-text and accepted-span rules. Smaller shared windows
slice selected row identities, including missing slots in compatibility adapters,
so a missing detail cannot pull an older row into a previously fixed sample.

Every successful read carries its requested limit and whether older eligible rows
exist. Complete means that this bounded request succeeded, not that all History
was read. Corpus exposes per-source status and coverage; strict analyser callers
reject partial or failed reads. Cancellation propagates instead of becoming a
successful empty corpus, promoting that original known-defect contract into the
normal suite. The former test that silently skipped a failed source now asserts
explicit partial status while retaining its successful ordering/filtering checks.

Words and Conjugations preserve their last usable result with a retry notice;
Words disables exports until a complete current result is available. Practice
retains its schedule and pending run on read failure. Patterns requires a fresh
successful evidence read before saving a delayed explanation. Chat targets cache
only complete builds, and a changed revision revokes both cached targets and
in-flight or timeout fallbacks. Its five-minute freshness and four-second wait
remain unchanged for known revisions. Unknown-revision adapters are reread and
cannot authorize a stale fallback.

The reader retains full `SessionDetail` records, including raw attempts, for its
largest requested window (at most 3,000 in production). It stores nothing on disk;
a revision watcher drops obsolete payload without eagerly rebuilding it. This
reduces database work, not the size of each record. It is not a guarantee of
instant erasure across independent stores: a failed Chat cleanup remains pending
until History acknowledges its deletion.

Scale fixtures compare the former page/detail path with the new read at 100,
1,000, 3,000 and 10,000 records. They check bounded IDs, ordering and derived
results and observe actual SQL queries. Separate delayed-read tests cover
cancellation, concurrent callers, mutation during a fill, retry, and cache
invalidation. Linguistic gap and sentence-boundary policies are unchanged.

The real SQLite fixture measurements below include deliberately hidden sessions;
60% of stored sessions qualify for the unchanged visibility rule. Each baseline
measurement reads Corpus, Patterns and Practice once through the former paging
protocol. Shared reads also repeat the consumers and project a language without
another database read.

| Stored sessions | Eligible sessions | Former SELECTs | Shared, 3000 first | Shared, 400 → 500 → 3000 | Retained records |
|---:|---:|---:|---:|---:|---:|
| 100 | 60 | 365 | 2 | 2 | 60 |
| 1,000 | 600 | 3,024 | 2 | 6 | 600 |
| 3,000 | 1,800 | 5,436 | 2 | 6 | 1,800 |
| 10,000 | 6,000 | 7,848 | 2 | 6 | 3,000 |

Those retained fixture records contain respectively 2,588, 29,048, 94,248 and
163,000 UTF-8 bytes of original/final text, raw attempts and suggestion text.
These are logical payload counts, not heap measurements or handset latency claims;
real provider answers can be much larger. The cache is bounded by records, not
bytes. `HistoryAnalysisScaleTest` emits the measurements as `HISTORY_READ_COUNTS`
and asserts parity and query counts, without timing thresholds.

The final normal suite passes all 1,988 tests with zero failures or skips, including
the optional corpus/fuzz fixtures: 63 new regressions and the promoted cancellation
contract. Three existing method names change to describe the explicit Corpus/Check
failure contracts and batch-independent ordering; their successful-output assertions
remain covered. All other baseline methods remain in the suite. Release lint and
assembly pass with the existing 66 lint warnings and one hint. The architecture
guard passes for 265 production Kotlin files, alongside all 125 Python tests.

## Known defects still executable

After step 6, the opt-in suite contains these five failing methods. A setup
exception, timeout, or unrelated assertion failure is not a successful reproduction;
check the failure against the intended invariant below.

| Class | Method | Desired invariant |
|---|---|---|
| `WriterEvidenceContractTest` | `aBracketedGapIsNotVocabularyKnowledgeForChatTargets` | A writer's bracketed gap is not evidence that they know the word. |
| | `aPhraseInsideABracketedGapIsNotDeckCoverage` | Phrase matching obeys the same gap exclusion. |
| | `spanishPerfectCannotJoinTwoSentences` | Compound tense recognition respects sentence boundaries. |
| | `catalanPeriphrasticPastCannotJoinTwoSentences` | Compound tense recognition respects sentence boundaries. |
| | `removingAGapDoesNotMakeItsNeighboursACompound` | Skipping a gap cannot manufacture adjacent compound tokens. |

## Test boundaries and production changes

Most view-model tests share one coroutine test scheduler for Main and injected IO.
Deferred provider replies and held dispatches establish the contested ordering;
there are no sleeps or polling loops. Activity lifecycle tests advance Robolectric's
virtual main looper. Memory tests retain real IO dispatch and use
explicit gates and job joins; their timeout only bounds a broken handshake.
Persistence tests use actual temporary files or SQLite. Nonempty directories at
temporary/destination paths force write/rename failure without relying on OS
permissions. New instances read the durable state where restart consistency matters.

Step 1 added optional dispatcher parameters on `ChatStore`, `ChatPartner`, and
`PatternsLab`, all defaulting to `Dispatchers.IO`. Steps 3 and 4 add the persistence,
failure handling and operation ownership described above, with scheduler seams
where needed to test actual store/coordinator behavior. `kotlinx-coroutines-test` remains a test-only dependency
at the existing coroutines version, with dependency verification metadata recorded.

This is a focused baseline, not complete lifecycle coverage. In particular:

- Chat tests drive the coordinator and store. They do not click Compose actions;
  Send-button availability after failed/unclear checks remains a separate UI contract.
  History-off is represented by the existing `DraftCheck.finish` null result.
- Notes, Chat and scoped-query tests cover selected interleavings, including late
  completion after cancellation. Reconstruction tests replace view-model owners
  and restore saved state; this is not a handset process-kill test.
- Unsent Chat drafts/checks and Notes edits not yet acknowledged at process death
  remain transient. Saved instance state is a snapshot: a later save cannot update
  a Bundle Android already captured.
- Accepted Notes commands drain on the application scope after the editor closes;
  they are not a persistent command queue. Words import/delete commands and failed
  deck-cleanup retries remain scoped to their ViewModel's lifetime.
- Corpus deletion fixtures check derived recomputation. Separate conversation
  cleanup tests cover durable pending work and acknowledged retries; cleanup still
  is not one transaction across all feature stores.
- File failure fixtures cover specific write/rename failures, not every disk-full,
  corruption, crash, or device-storage scenario.
- Corrected memory provenance applies to new learning updates. Older files do not
  retain previous claim text, so incorrectly merged historical support cannot be
  reconstructed automatically.
- Export tests cover captured evidence, queued writers, retirement and exact-file
  ownership; they do not drive Android's share sheet or another app reading a URI.
- Provider fakes stop at request preparation and parsing. HTTP behavior, live model
  quality, and handset behavior remain outside these tests.
