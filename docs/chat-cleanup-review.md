# Chat cleanup review

Step 5 starts at `a288702`. The pre-audit comparison is `5b5ecd9`.
Four parallel reviews covered the whole Chat subsystem, including unchanged code:
state and lifecycle, storage and deletion, UI and consumers, and independent
behavior-contract review. This is a bounded cleanup, not a replacement architecture.

## Assessment

The main problem remains concentrated responsibility in `ChatViewModel`: draft
checking, selection, creation, message persistence, replies, metadata, and learning
all meet there. Before this patch it was 868 lines, 286 above the pre-audit version.
Moving those methods into another coordinator would not itself simplify the system.
The useful reductions found here remove state that must be kept in agreement,
duplicate callbacks, and dependencies with no feature responsibility behind them.

The complete path reviewed was route ownership → draft/check revision → accepted
outgoing message → SQLite acknowledgement → History/timing acknowledgement →
partner generation → conditional append → visible thread. The review also followed
retry, current-route restoration, rename, deletion and restart cleanup, translation,
learning, and History/Patterns/Practice consumers.

## Decisions

| Area | Change | Preserved contract |
|---|---|---|
| Reply-save failure | Derive the flag from the retained unsaved answer; make that payload observable Compose state. | Retry saves the same generated text, translation and title without another provider request. The failure disappears while retrying and returns on another storage failure. |
| Read failure | Derive the flag from the observable retained retry action. | Failed reads preserve prior data and remain distinct from a successful missing/empty result. A list refresh cannot clear an unrelated thread failure. |
| Card actions | Use the existing `CardActions.of` factory in Chat. | Chat keeps its captured review revision, Send label, and absent phrase-save control; other hosts keep their defaults. |
| Cross-feature labels | Inline two one-use resource mappings and remove `ChatEntries`. | Patterns and Practice retain their different null-origin labels, without importing Chat for them. |
| Deletion cleanup | Reuse the existing exception boundary and remove temporary acknowledgements. | Every independent cleanup is attempted after earlier failures, cancellation propagates, and pending work retires only after all acknowledgements. |
| SQLite | Use the existing message cascade and inline the single-use learned-count update helper. | Raw session IDs are collected before deletion; existence/permit ordering and atomic queue insertion remain unchanged. |

The deletion queue read now uses the existing generic cleanup warning when it
fails. The result and retry behavior are unchanged. No schema migration is added.

## Boundaries retained after challenge

- Selection and editor identity have different lifetimes. An accepted writer save
  finishes its captured check and retires its draft after navigation; an obsolete
  generated reply must lose permission to append. Cancellation alone cannot reject
  queued, non-cancellable SQLite work.
- Partner append checks both request permission and the durable last-message ID
  inside the transaction. A successful writer append returns the authoritative
  thread, including replies committed before their Main-thread acknowledgement.
- Rename permits protect database ordering; acknowledged rename tracking protects
  against an older append snapshot arriving later. Neither replaces the other.
- Deletion retains raw History links from unreadable message rows, durable retry
  work, and exact queue acknowledgement. Immediate memory invalidation and later
  queued cleanup serve different timing guarantees.
- Screen disposal and `leave()` keep their existing learning/lifecycle semantics.
  This patch does not change navigation, checking, prompting, or learning policy.

Existing behavioral tests demonstrate these distinctions, but do not establish
that every guard is individually necessary. In particular, a writer save through
ViewModel clearing and a held append acknowledgement after a later manual rename
still lack isolated integration tests. Their safeguards remain in place.

The remaining test-only legacy store APIs and broader ViewModel decomposition are
deferred. Removing them requires an explicit contract/ownership decision; relocating
their code or weakening tests would not be a meaningful reduction.

## Verification and size

The 180 focused Chat/storage/card checks pass. The complete run passes 2,018
regression tests with no failures, errors or skips: all 2,017 previous test IDs
remain, plus one actual review-screen regression. Existing tests also gain held
reply-save retry and upgraded-database cascade assertions. No test is removed.

All 144 Python checks and the architecture check pass. Release lint and assembly
pass; lint retains 65 warnings and one hint. All 200 archived replay outputs match
the previous commit. The five opt-in known-defect assertions still fail with the
same failure signatures; they remain separate from the passing regression suite.

Against `a288702`, production Kotlin changes by **−48 lines** (+44/−92) and tests
by **+138 lines** (+143/−5). Documentation is counted separately. Cumulative
production growth against `5b5ecd9` remains **+2,070 lines**. `ChatViewModel` is
still 851 lines; this patch removes duplication without claiming to resolve its
broader concentration of responsibilities.
