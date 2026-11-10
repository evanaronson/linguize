# Bonanza behavior contracts

These tests establish a baseline before cleaning up `feature/bonanza`, starting at
`d5d92cc13f083411ff60735c59a743ac20780704`. They preserve successful behavior and
make confirmed failures reproducible. They do not fix those failures.

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

**The second command is expected to exit nonzero at this baseline.** Its failures
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
| `NotesAsyncContractTest` | Input during initial restore survives; switching notes saves outgoing edits and restores the selected note's language/title. |
| `PatternsAsyncContractTest` | Delayed explanations retain their evidence scope; provider and History failures preserve the last result and allow retry. |
| `WriterEvidenceContractTest` | Real word, vocabulary, and conjugation consumers preserve accepted-span counts, original examples/offsets and language isolation; rebuilding after deletion removes unique source evidence. |
| `ComposedCheckContractTest` | The actual base + gaps + tone request has fixed system/schema fingerprints, payload, options and routing for each provider; reply repair retains captured context; fresh and legacy parsing stay distinct. |
| `PersistenceBoundaryTest` | Draft write failure preserves cache and disk and permits retry; imported decks and selection round-trip; Practice returns the schedule it saved. |

The composed-request fingerprints were independently captured from the reviewed
assets. Expected values are not computed by the composition functions under test.
Review prompt-policy changes before updating these fixtures. These checks do not
measure a live model's judgment or replace the model evaluation suite.

## Known defects made executable

At this baseline, the opt-in suite contains these 20 failing methods. A setup
exception, timeout, or unrelated assertion failure is not a successful reproduction;
check the failure against the intended invariant below.

| Class | Method | Desired invariant |
|---|---|---|
| `ChatBehaviorContractTest` | `aFailedWriterSaveKeepsTheDraftAndDoesNotRequestAReply` | A rejected SQLite write leaves recoverable input and cannot be treated as a sent message. |
| | `openingAnAutomaticallyCreatedConversationWaitsForItsPendingCreation` | Navigation cannot report the conversation being created as missing. |
| | `aManualTitleSetDuringAReplySurvivesInStorage` | A late automatic title cannot overwrite a manual title on disk. |
| | `cancellingAnOlderReplyDoesNotClearTheNewReplyStatus` | An older request cannot clear the active request's loading status. |
| | `delayedCreationDoesNotReplaceANewerSelectionAndItsDraft` | Late creation cannot replace the user's newer selection and draft. |
| `LabUserMemoryConcurrencyTest` | `clearingMemoryInvalidatesAnAlreadyRunningLearningRequest` | Clear invalidates learning requested before Clear. |
| | `forgettingAQueuedSourcePreventsItsMessagesFromReachingTheProvider` | A deleted source cannot leave the learning queue for the provider. |
| | `forgettingTheSourceOfReplacementTextDoesNotAttributeItToTheOldSource` | Deleting the only evidence for replacement text cannot leave it attributed to an older source. |
| `MemoryStoreFailureContractTest` | `failedDeletionDoesNotAcknowledgeOrPublishAnUnsavedChange` | Failed deletion cannot appear committed or offer successful Undo. |
| `PersistenceBoundaryTest` | `failedPhraseSaveMustNotPublishAnUndurablePhrase` | Observable phrases agree with the durable store after a failed save. |
| | `failedPracticeUpdateMustNotAcknowledgeAnUnsavedGrade` | A returned schedule is durable, or the write reports failure. |
| | `failedSelectionRenameMustBeReported` | Failed file replacement must not return success. |
| `NotesAsyncContractTest` | `typingWhileAnotherNoteOpensIsNotReplacedByTheDelayedRead` | Input accepted during a switch survives the delayed read. |
| `PatternsAsyncContractTest` | `aSpanishExplanationCompletingAfterALanguageChangeCannotReplaceTheCatalanReport` | The selected language and displayed report remain coherent. |
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

The only production changes are optional dispatcher parameters on `ChatStore`,
`ChatPartner`, and `PatternsLab`, all defaulting to the previous `Dispatchers.IO`.
No production policy, persistence format, prompt, schema, or UI behavior changes.
`kotlinx-coroutines-test` is a test-only dependency at the existing coroutines
version, with dependency verification metadata recorded.

This is a focused baseline, not complete lifecycle coverage. In particular:

- Chat tests drive the coordinator and store. They do not click Compose actions;
  Send-button availability after failed/unclear checks remains a separate UI contract.
  History-off is represented by the existing `DraftCheck.finish` null result.
- Notes and Patterns cover selected interleavings. Words' overlapping queries,
  route restoration, Android process death, and Practice restoration need further tests
  when those areas are changed.
- Corpus deletion fixtures check derived recomputation, not the entire cross-store
  deletion workflow. The tests do not prove a deletion transaction across stores.
- File failure fixtures cover specific write/rename failures, not every disk-full,
  corruption, crash, or device-storage scenario.
- Provider fakes stop at request preparation and parsing. HTTP behavior, live model
  quality, and handset behavior remain outside these tests.
