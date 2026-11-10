# Architecture

Linguize is one Android app with one Gradle module, `:app`. Keep that shape while
its components ship together and share behavior across the app's entry points.
Packages give the edit engine, model clients, history, and Android hosts clear
owners without adding a module or framework for each responsibility.

## Repository map

| Location | Owns |
|---|---|
| [app/src/main/java/com/evanaronson/linguize/](../app/src/main/java/com/evanaronson/linguize/) | Production Kotlin, described below |
| [app/src/main/assets/](../app/src/main/assets/) | Shipped model prompts, answer schemas, and linguistic data; Kotlin prepares app and eval requests from the same assets |
| [app/src/main/res/](../app/src/main/res/) | Android strings, themes, icons, and platform configuration |
| [app/src/test/](../app/src/test/) | JVM and Robolectric regressions, generally mirroring production packages |
| [eval/](../eval/README.md) | Curated linguistic cases and checks against live models |
| [scripts/](../scripts/) | Repository checks and their offline tests |
| [brand/](../brand/) | SVG deliverables and the Python geometry that generates them |
| [gradle/](../gradle/) | Dependency versions, wrapper, and download verification metadata |
| [Build workflow](../.github/workflows/build.yml) | Tests, lint, unsigned build, and a separate signing/publishing job |
| [docs/](README.md) | Current contracts and conventions; historical designs live in `archive/` |

Keep the prompt under app assets so it ships with the app and has one source.
Keep tests next to their platform's normal source set so Gradle and IDEs discover
them. The SVGs include wordmarks, icon variants, and construction studies; they
remain useful design outputs even when Android does not load them. Their generator
writes SVGs; the Android vector drawables are separate copies.

## Package ownership and direction

The package root is `com.evanaronson.linguize`. In the table, a package can also
import itself. `R` and `BuildConfig` are generated resources, separate from the
composition root.

| Package | Responsibility | Other app layers it may import |
|---|---|---|
| `codec` | Stable enum tokens and serialization | None |
| `core` | Check/edit rules, corpus and language analysis, chat send state, memory facts, and practice scheduling | `codec` |
| `llm` | Prepared check requests, prompt formatting/parsing, provider APIs, transport, and check failures | `core`, `codec` |
| `history` | Recorded evidence, recording rules, storage, answer reuse, and replay | `core`, `codec` |
| `data` | Preferences, credentials, feature stores, imported decks, selection-menu configuration, and exports | `llm`, `core`, `codec`, resources |
| root | `App` composes services; `Checker`, `ChatPartner`, `ChatTargets`, `PatternsLab`, `Teacher`, and `LabUserMemory` coordinate feature operations | All of the above, resources |
| `ui` | View models, shared card, app screens, navigation, and localized presentation | Root and all lower layers |
| `menu`, `accessibility` | Android entry points and returning changes to the source app | `ui`, root, and all lower layers |

`codec` and `core` stay free of Android imports. `llm` and `history` are peers:
the model client does not read history, and stored records do not depend on a
provider implementation. [CheckContext](../app/src/main/java/com/evanaronson/linguize/CheckContext.kt)
adapts a check's settings to the form history stores at the composition boundary.

[The architecture check](../scripts/check_architecture.py) enforces package
declarations and these import directions before Gradle runs in CI. It treats
`ui` as one layer and checks production Kotlin, not tests. It does not detect
fully qualified references or prove that library dependencies are platform-free;
those still need review. Update this document and the guard together when a
real dependency change is needed.

## Where behavior belongs

The selection-menu, clipboard/share, accessibility, Notes, and Chat check hosts share
[ui/card](../app/src/main/java/com/evanaronson/linguize/ui/card/).
`CheckViewModel` owns the visible check and accepted edits; `CardActions` gives
hosts the same card actions. The selection-menu activity and accessibility service
own their different Android lifecycles and replacement mechanisms. They do not
import each other.

[ui/home/HomeActivity](../app/src/main/java/com/evanaronson/linguize/ui/home/HomeActivity.kt)
owns the app scaffold and the Chat, Notes, Progress, and History tabs, with Settings
and feature/detail pages above them. The Notes tab is a list; `Screen.Note(id)` opens
an editor explicitly. `Navigation` saves a stack of `Screen` tokens, including a
Progress block's language when opened under Auto. Ordinary routes follow the app
selector; a block route retains its language through recreation and Settings/Back.
A real app-language change clears block overrides, while the initial restored
selection does not. Detail routes retain their intrinsic language or inherit their
parent's block scope. The visible page applies its effective scope to its model;
opening another page no longer changes every feature's selection as a side effect.
Delayed current-note and current-conversation resolutions must still belong to the
same live navigation entry. Popup navigation and return handling live in `ui/popup`.

[History](history.md) owns the meaning of stored decisions. The pure
`SessionRecording` turns card activity into records; `CheckHistory` coordinates
writes and reuse; `HistoryStore` is the storage boundary. SQL, migrations, and row
mapping stay beside that feature. The edit engine does not know recording exists.

`data` owns the app's other persistence. Chat keeps conversations, raw/sent text,
accepted changes, translations, and optional History links in its own SQLite
database. It remains usable with History disabled; History-only deletion does not
delete the conversation. Notes, saved phrases, memory facts, practice schedules,
imported decks, lessons, pattern explanations, and measured time have separate
stores. These stores have different retention rules: a deliberately saved phrase,
for example, survives deletion of its source check. See the feature documents in
[docs/lab](lab/) for their file formats and policies.

Authoritative file stores acknowledge a change only after replacing the file.
[data/AtomicTextFile](../app/src/main/java/com/evanaronson/linguize/data/AtomicTextFile.kt)
writes and syncs a temporary file, then renames it over the destination without
deleting the old destination as a fallback. Stores publish their cached state after
that succeeds. Read and write failures remain failures: a missing file can mean an
empty collection, but an I/O failure cannot. Screens retain pending input or an
uncommitted operation so it can be retried. This is a file replacement boundary,
not a transaction across features or a guarantee against every power-loss scenario.

In particular,
[data/Exports](../app/src/main/java/com/evanaronson/linguize/data/Exports.kt)
manages files through an output-stream callback, so it need not know the history
schema. History and Words share the application's export owner and use a unique
file for each share. Retirement invalidates captured evidence and queued writers;
discarding one result addresses that exact file, so an old callback cannot remove
a newer export. UI code owns sharing and requests cleanup. The files named
`Exports.kt` in these layers have different responsibilities.

`App` owns lazily created shared services and a process-wide `appScope`. The scope
runs recording/cleanup work, memory learning, cached chat-target builds, and the
pattern explanation watcher. It also drains accepted Notes commands after their
view model closes; all Notes hosts share `App.notesStore`. Screen queries generally
run in `viewModelScope`. Words deck mutations and failed cleanup IDs remain owned
by that view model; they have no durable queue or process reconstruction.
A process scope outlives a screen, not the process: Chat deletion removes
its local rows and records pending cleanup in the same SQLite transaction. Cleanup
of History, time and learned memory is idempotent; the pending record survives a
restart and is retired only after those stores acknowledge completion. The stores
remain separate, so their cleanup can finish at different times.

Asynchronous results carry the identity of the work that requested them:

| Feature | Ownership boundary |
|---|---|
| Chat | Selection, editor input and partner requests have separate ownership. Store-side checks protect delayed reply appends and title changes; an acknowledged writer append returns the current stored thread. |
| Notes | Each requested editor buffers its own input while loading. Ordered commands and shared-store selection, input and deletion revisions protect saves, opening and Undo across hosts. Accepted commands outlive a page, but not the process. |
| Practice | Saved instance state holds run/item identities, small answer decisions and numeric schedule baselines. History rebuilds the text; the durable schedule determines whether restored work is still current. |
| Words | Evidence, language, deck and level form one published result; superseded analysis cannot supply its coverage or export. Storage mutations run separately from replaceable query work. |
| Patterns | A request captures language and evidence generation before work starts. Explanation save/delete and evidence reconciliation share the service's storage lock; provider calls run outside it. |
| Time and Conjugations | Refresh replaces older work. Time checks cancellation before sweeping measurements and labels its report with its captured language; Conjugations builds both language reports from one read and preserves the current display selection. |

Cancellation releases obsolete work; ownership checks also protect publication
and storage when a dependency finishes late. These guards do not themselves
reconstruct pending requests after process death. Practice has a separate compact
checkpoint: it rebuilds its queue from live History and reconciles saved decisions
against the durable schedule before resuming. An advanced schedule can supersede
ambiguous stale work; restoration does not invent a grade or replay an acknowledged
one. The checkpoint copies no History text and adds no schedule-file schema. See
[Practice](lab/practice.md#returning-to-a-run) for its reconstruction limits. The
evidence windows below remain unchanged.

Keep Android resources in their native form. `strings.xml`, `strings_lifecycle.xml`,
and `strings_platform.xml` form one catalog; file boundaries do not affect resource
IDs or translation fallback. Follow [localization](localization.md) and
[copy style](copy-style.md) when changing visible text.

## Model requests

`App` supplies the base check prompt plus gap and tone rules to `Checker`.
`CheckComposition.prepare` produces a `PreparedCheck`: the complete logical
`GenerationRequest`, the existing compatibility hash, and the base fresh-answer
reader. Instructions remain ordered base, gaps, tone, then reply; chat adds the
partner's last message as one JSON line before the ordinary user message.
`Checker` sends every check through the provider's `generate` API and passes its
raw answer to `PreparedCheck.read`. Optional tone data is interpreted separately,
so a missing or malformed `landing` does not invalidate a usable check.

`CheckContext` captures settings and the compatibility hash for recording and
reuse. That hash retains its historical construction: base hash, optional reply
suffix, then gap and tone suffixes. It is not a new digest of the composed request.
Fresh answers keep the base contract's required fields and score validation;
`Checker.reuse` retains the lenient parser for saved older answers. A usable but
incomplete answer may receive one repair request with the same captured options.
`selectRepairedCheck`, also used by the explicit historical replay harness,
chooses the whole repaired answer only when its withheld judgments are a strict
subset of the first answer's. It never splices answers or ranks them by edit count.
Model testing prepares and reads the same plain base check through `generate`;
provider clients own transport and model discovery, without prompt dependencies.

Chat replies, memory extraction, lessons, and pattern explanations use their own
contracts through the same provider clients. The command-line model eval obtains
initial and repair requests from the actual Kotlin preparation code. Its default
`app` mode includes gaps and tone, plus reply context when supplied; explicit
`base` mode keeps archived base-only evidence identifiable. Case inputs supply
the settings: omitted `writing_profiles` means an empty list, not a fresh
installation's profiles. Dataset, effective contract and request digests are
separate metadata and do not replace History's compatibility hash.

This shares logical `GenerationRequest` fields and their identities. Python still
owns its HTTP adapters, retry state and response handling; it does not run the
app's parser or edit engine. The [eval guide](../eval/README.md) describes exporting
and pinning requests. The [Bonanza contract tests](bonanza-contracts.md) cover
request bytes, provider envelopes, repair and parsing without live provider calls.

## Evidence and derived learning

History supports browsing, replay, and answer reuse, and already supplies learning
features. Its raw model answers and final decisions are observations, with the
[limits described in the history contract](history.md#what-the-records-can-support).

| Consumer | Current evidence path | Derived state |
|---|---|---|
| Words and Conjugations | `App.corpus` → `CheckCorpusSource` → `App.historyEvidence`, up to 3,000 saved checks | Word index/deck coverage and morphology reports, rebuilt from original writer text and changed-word spans |
| Chat practice targets | The same 3,000-check corpus → `PracticeTargetsBuilder` | Per-language target blocks; vocabulary uses `usedDeckKeys` and `WordFolding`, while Words uses `WordIndex`/`DeckCoverage` |
| Patterns | `App.historyEvidence`, up to 400 checks | Deterministic report plus an optional saved model explanation with source IDs and fingerprint |
| Practice | `PracticeSource` → `App.historyEvidence`, up to 500 checks | Rebuilt items plus separately stored review schedules; the existing policy prunes schedules outside the successfully loaded set |
| User memory | Chat exchanges → `LabUserMemory`, independently of the corpus | Model-proposed facts and writer-owned notes in `MemoryStore`; `ChatPartner` includes enabled memory in its prompt |

`App.historyEvidence` is one `HistoryEvidenceReader` bound to one History store.
SQLite reads the selected sessions and their suggestions in one transaction and
binds that snapshot to its process-local committed revision. The reader shares the
largest successful window among consumers at that revision. Smaller windows slice
the original selected IDs in rank order; they do not backfill missing slots with
older records or turn a 400-check request into a 3,000-check analysis. A commit that
overtakes a read invalidates it and allows one retry. Stores without an authoritative
revision use the compatibility page/detail reader and do not populate this cache.

The shared cache retains full `SessionDetail` objects, including original text,
suggestions, attempts and raw model answers. It is bounded by the largest requested
record window (3,000 for the registered consumers), not by bytes or a lean evidence
projection. It has no per-screen or time expiry: a larger read replaces the window,
a History revision invalidates it, and process death releases it. The application's
revision watcher also releases obsolete cached payloads between reads. This is a
process cache, with no new database table or migration.

`CheckCorpusSource` is the only source registered in `App.corpus`. It reads eligible
History records, including recorded Chat messages, rather than scanning Chat's
independent store. A conversation with History disabled therefore remains visible
in Chat but does not contribute new corpus evidence. `CheckUtterances` preserves
original text, source/session identity, language, changed-word spans, filled gaps,
and the existing reuse and rechecked-output exclusions.

`Corpus.read` reports `Complete`, `Partial` or `Failed`, with per-source coverage:
requested limit, whether older records exist, revision when known, and selected and
loaded record counts before mapping. `Complete` means the requested bounded read
succeeded; it does not mean all History was read, or that every loaded record became
an utterance. A successful empty window is distinct from a failed read. Partial
results remain inspectable, but `Corpus.utterances()` requires complete evidence
and throws on partial or failed reads. Cancellation propagates through the shared
reader, corpus and consumers; it is not converted into empty or partial evidence.

Chat targets retain their five-minute freshness interval and four-second wait.
Only successful complete builds become cached targets. A failed or timed-out
refresh may use a previous target block only while its known evidence revision
still matches. A revision change revokes fresh blocks, pending old builds and
fallbacks; unknown revisions require another read and cannot use failure/timeout
fallback. A timed-out caller does not cancel the shared build, which may finish for
a later caller. A configured supported-language deck that cannot load is a
retryable failure, not a successful register-only block; unsupported languages and
explicitly absent decks remain valid configurations.

These boundaries leave the linguistic algorithms, target ordering and evidence
windows unchanged. Reports and persisted derived state still have independent
retention rules. Patterns reconciles explanations against its bounded report;
Practice keeps its existing schedule policy; saved phrases, memory, Notes and Chat
records do not become owned by the shared reader. History deletion removes records
from subsequent successful evidence reads. Chat deletion's queued cleanup can
reach History later, and an older window is not an inventory of all surviving
records. Removing the former Vocabulary gaps screen did not make its vocabulary
engine obsolete: Chat targets and shared deck matching still use it.

Two kinds of improvement need different evidence. A wrong linguistic suggestion
belongs in a reviewed eval case with an expected judgment. A lookup failure,
lost replacement, malformed answer, or lifecycle regression belongs in the
corresponding Android or offline test. A transport error is not a writing mistake.
The [eval guide](../eval/README.md) describes how curated cases support prompt and
provider changes.

A count of accepted suggestions alone cannot establish correctness, authorship,
or improvement over time. Preserve source identity and current counting rules when
refactoring; review semantic changes separately. Existing stored fields, enum
tokens, legacy decoding, and database migrations remain compatibility boundaries.

## When to split further

A separate Gradle module becomes useful when a component needs enforced build
isolation, a second consumer, or an independent build. A package split becomes
useful when it separates actual owners or removes an import cycle. Neither is
needed merely because another app version is planned.

For now, extend the owning package, keep domain calculations testable without
Android, and reuse the existing card and storage boundaries. See the
[README](../README.md#build-locally) for the checks required by the build.
