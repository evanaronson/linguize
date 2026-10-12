# History today

History stores checks on the phone, shows recent and past results, and can reuse
a recent answer. It does not infer recurring mistakes, personalize the prompt,
or sync to a server. The [October 2026 design](archive/history-design-2026-10.md)
preserves earlier decisions; this document describes the implemented contract.

## Ownership

All recording code lives in
[history/](../app/src/main/java/com/evanaronson/linguize/history/).

| File | Responsibility |
|---|---|
| [HistoryModel.kt](../app/src/main/java/com/evanaronson/linguize/history/HistoryModel.kt) | Records, stable decision/outcome tokens, reuse keys, and the decided attempt |
| [SessionRecording.kt](../app/src/main/java/com/evanaronson/linguize/history/SessionRecording.kt) | Pure Kotlin rules for turning one card's activity into records |
| [CheckHistory.kt](../app/src/main/java/com/evanaronson/linguize/history/CheckHistory.kt) | Recording eligibility, ordered writes, and racing a request against a stored answer |
| [HistoryStore.kt](../app/src/main/java/com/evanaronson/linguize/history/HistoryStore.kt) | Storage interface and protection against delayed writes restoring deleted sessions |
| [SqliteHistoryStore.kt](../app/src/main/java/com/evanaronson/linguize/history/SqliteHistoryStore.kt) | Android SQLite I/O and transactions |
| [HistorySchema.kt](../app/src/main/java/com/evanaronson/linguize/history/HistorySchema.kt), [HistoryJson.kt](../app/src/main/java/com/evanaronson/linguize/history/HistoryJson.kt) | SQL, upgrades, row mapping, attempts JSON, and export encoding |
| [Replay.kt](../app/src/main/java/com/evanaronson/linguize/history/Replay.kt) | Reconstructing a saved card from its answer and recorded suggestions |

`CheckViewModel` reports check results, accepted changes, copies, and closure.
The host reports whether it handed accepted text back. `App` supplies the store,
recording settings, and process-wide coroutine scope.

## What is recorded

Recording is on by default. It covers checks started from another app's selection
menu or the accessibility button. The app's own Try it card is excluded. History
also skips overlong inputs and cards opened while recording is off. Switching
recording off stops further writes for an open card; switching it on again does
not resume that card. Saved records stay until deleted.

One card is one session, including retries, language changes, and settings changes.
A session keeps the original text, source package when known, timestamps, app
version, and a random installation ID. Each completed attempt keeps its raw answer
when available, settled assumptions, failure tokens, and the language, provider,
model, options, native language, and prompt hash it ran with. A reused answer also
records its source session as `reusedFrom`.

The session is queued for saving when recording opens, then updated after each
completed attempt. Accept, undo, and copy activity stays in memory until closure.
Closing queues the final session and suggestion rows in one transaction. Writes
run off the main thread on a scope that outlives the screen, but not the process.
The selection-menu host waits up to 500 ms for queued writes when closing.

On the next process start, saved sessions still open from an earlier process are
marked `abandoned`. This cannot recover unsaved actions or a write that never
reached storage. History failures are logged and normally return fallback values
so they do not break checking. Recording is therefore best effort, not a complete
event log or a guarantee that every request survives process death.

## Decisions and outcomes

Suggestions are anchored to the original text and linked to an attempt. Their
decision describes the state at closure:

| Decision | Meaning |
|---|---|
| `accepted` | Included in the accepted text the host reported handing back |
| `undone` | Accepted at some point, then absent from the handed-back or copied result; this also covers failed replacement |
| `retired` | A fix covered by a rewording that was applied or copied |
| `copied` | Included in a version copied through the card's Copy action |
| `ignored` | Still offered when the card closed, without another recorded decision |
| `superseded` | Belonged to a revision replaced by a later check or language change |

For the final revision, applied changes take precedence, then retired, copied,
undone, and ignored. If no text was handed back, nothing is `accepted`. An earlier
revision replaced by another is recorded as `superseded`, without its intermediate
accept/undo trajectory. `decidedAt` is closure or supersession time, not each tap's
timestamp.

The session outcome is `applied`, `copied`, `failed`, `none`, or `abandoned`.
Applied takes precedence over copied; copied takes precedence over a failed last
attempt. `status` separately records the last successful linguistic result:
`ok`, `unclear`, or `wrong_language`. A technical failure and an unclear sentence
are different observations.

## Reuse and replay

A first check, or the first check after a language change, starts the model
request before looking for history. A matching stored answer can win the race;
then the app shows it and cancels the request. This can save waiting, but it does
not guarantee that no request reaches the provider or incurs usage.

Candidates are closed sessions whose last attempt succeeded, started within ten
minutes, with the same text hash and matching language and check settings,
including the prompt hash. The chosen answer's own attempt must match, even if
settings changed later on its card. Lookup is disabled for unrecorded checks.
Normal rechecks ask the model again.

A saved page uses the *decided attempt*: the successful attempt owning the final
non-superseded suggestions, otherwise the last successful attempt. Replay uses
stored suggestion spans and acceptance decisions when today's edit engine would
produce different edits. It still requires a readable answer and valid spans;
unreadable or incompatible records may not yield a card. Replay does not rewrite
the recorded evidence.

## Storage, deletion, and export

The SQLite database has `sessions` and `suggestions` tables. The database is at
version 5; row schemas are at version 4. Migration code preserves earlier formats,
including old enum names and language names, and removes old Try it sessions.
Keep these migrations and stable tokens when reorganizing code.

Recent shows five closed checks; History pages through older ones by timestamp
and ID. Failed checks remain visible. Settings counts closed, undeleted sessions.

Delete and Delete all perform hard deletes. `deletedAt` remains reserved and is
not populated, so there are no sync tombstones. The store tracks open/deleted IDs
to prevent delayed writes in the same process from bringing removed sessions
back. The list's Undo flow saves pending deletion notes and retries them after a
process restart. Detail-page deletion waits for storage confirmation before
leaving the page; a failed write keeps the check visible and allows another try.
It and Delete all do not persist retry notes. Process failures can still interrupt
those operations.

SQLite uses `secure_delete`; deletion requests a WAL checkpoint, and Delete all
also requests a vacuum. Export-file cleanup is attempted after deletions and on
expiry. These mechanisms should not be described as verified erasure across all
failure cases or of copies already shared elsewhere.

Export writes JSON Lines, one session with its suggestions per line, directly
from stored rows so unknown values are retained. Only a completed export is
offered through the share sheet. Temporary files have a five-minute expiry policy
and are also cleared during later cleanup. History has no uploader. Checking
sends text to the selected AI provider, and explicit export can send saved history
to another app. Android backup and device-transfer rules exclude app data; stored
history text is not separately encrypted.

## What the records can support

Raw answers, settings, version information, and final decisions allow inspection
of captured checks and help reproduce or curate regressions. They do not validate
the model's linguistic claims. Acceptance is a user action, ignored is not a
rejection, and undone does not distinguish disagreement from a failed handoff.

The menu can check read-only selections; history does not record authorship or
whether the source was editable. The menu reports returning an Android result,
while accessibility reports the text-action return value. Neither proves the
writer subsequently used or sent the text.

Cancelled requests, failures before a history session opens, unsaved decisions,
and storage failures leave gaps. Reused answers and repeated checks are not
independent mistakes. The data contains no full measure of the writer's writing
or correctness, so it cannot by itself establish learning progress or an error
rate. See [Architecture](architecture.md#evidence-first-derived-learning-later)
for the prerequisites for deriving learning from these observations.
