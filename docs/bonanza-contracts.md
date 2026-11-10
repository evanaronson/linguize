# Bonanza behavior contracts

These tests establish a baseline before cleaning up `feature/bonanza`, starting at
`d5d92cc13f083411ff60735c59a743ac20780704`. They preserve successful behavior and
make confirmed failures reproducible. Step 3 repairs persistence acknowledgements
and memory command invalidation; step 4 gives asynchronous work explicit ownership.
Their repaired contracts now run in the normal suite.

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

## Known defects still executable

After step 4, the opt-in suite contains these six failing methods. A setup
exception, timeout, or unrelated assertion failure is not a successful reproduction;
check the failure against the intended invariant below.

| Class | Method | Desired invariant |
|---|---|---|
| `WriterEvidenceContractTest` | `aBracketedGapIsNotVocabularyKnowledgeForChatTargets` | A writer's bracketed gap is not evidence that they know the word. |
| | `aPhraseInsideABracketedGapIsNotDeckCoverage` | Phrase matching obeys the same gap exclusion. |
| | `spanishPerfectCannotJoinTwoSentences` | Compound tense recognition respects sentence boundaries. |
| | `catalanPeriphrasticPastCannotJoinTwoSentences` | Compound tense recognition respects sentence boundaries. |
| | `removingAGapDoesNotMakeItsNeighboursACompound` | Skipping a gap cannot manufacture adjacent compound tokens. |
| | `corpusPropagatesCancellationInsteadOfReturningSuccessfulEmptyEvidence` | Cancellation cannot become a successful empty corpus. |

## Test boundaries and production changes

View-model tests share one coroutine test scheduler for Main and injected IO.
Deferred provider replies and held dispatches establish the contested ordering;
there are no sleeps or polling loops. Memory tests retain real IO dispatch and use
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
  completion after cancellation. Route restoration, Android process death and
  Practice restoration remain separate work.
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
