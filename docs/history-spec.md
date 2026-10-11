# History

Status: built (v1), branch `feature/history`. What isn't built yet is marked below (the requirements checklist lists it). Where this document and the code disagree, the code wins.

## Decisions (9 Oct)

| Question | Decision | Consequence |
|---|---|---|
| Who reads it, where | On the phone first; a server later | Rows are upload-ready; no client-side encryption the server couldn't undo |
| Feeds back into checks? | No. A writer profile is premature | Checks stay stateless; history is write-only until insights |
| Full text or corrections only | Full text, keep everything | `text`, `finalText`, `meaning` and raw verdicts are stored |
| Second platform | Not a consideration for this feature | Framework SQLite; data classes stay Android-free anyway |
| Categories | None at check time. Patterns are found later by a model reading the raw edits and their reasons | No taxonomy, no `category` field; raw verdicts and `why` are kept |
| Raw data access | Should be available | Export in v1 |
| Where history lives | Home-first: the launcher screen becomes Home with Recent; Settings behind a gear; no button on the card | See UX |
| Other users | Possibly friends, not public | On by default, plainly worded; consent moment designed before friends install |
| What's recorded (10 Oct) | Only checks of text from other apps (selection menu, accessibility button), not the app's own "Try it" card | `Origin.recorded`; version 4 deletes the tester's old sessions |
| What a record is (10 Oct) | One record per popup: nothing a writer does while it's up (another language, other settings) makes a second one | Each attempt keeps the language and settings it ran with; the session has the latest attempt's |

Linguize remembers every check and what came of it, on the phone, so that later it can tell you the kinds of mistakes you tend to make. This document is the analysis and the spec for the recording half. The insight half ("you drop accents on verb endings") is a later feature that can't exist until there is history to read.

## The problem

Every check is thrown away the moment the card closes. The model sees the same mistakes from the same person week after week and nothing accumulates. The future feature needs months of data, so the recording has to start now, and it has to be recorded in a shape the future feature can use, or we'll be re-running the model over old text to recover what we could have kept.

Two things make this harder than "write a row":

1. **It's private.** The texts are messages to a partner, friends and colleagues. Today the app holds nothing but settings and encrypted keys. After this, it holds a diary. That changes what the app is, and the UX has to say so plainly.
2. **It has to outlive its first storage.** The data should live on the phone now and be able to move to a server later without a rewrite, and the insight feature should be able to read it without a migration.

## Goals

- Every check is recorded from the moment it starts, including ones that fail, with what was suggested and what the writer did about each suggestion.
- The record is enough to answer, later: *which kinds of mistakes, in which language, how often, and did the writer take the fix or not.*
- Recording never slows the card down or changes how it behaves.
- The writer can see that it's happening, turn it off, and delete it.
- The same data model works unchanged as the payload of a future sync.

## Non-goals (this version)

- **Insights.** No "you tend to…" screens. There's nothing to say yet.
- **Sync, server, accounts.** The model is ready for it; none of it is built.
- **Search** across history. Lists and delete only.
- **Encrypting history at rest** beyond the app sandbox. See *Privacy* for why, and when to revisit.
- **Retention policies** (auto-delete after N days). Clear-all and delete-one are enough for one writer.

## How it fits the app

The app already has one place that knows everything about a check: `CheckViewModel` owns the text, the language, the settled answers, the revision (every suggested edit and what's accepted), and the moment the card closes. Recording hooks in there and nowhere else; reading a past session back into a card (`history/Replay.kt`) is history's too. The three hosts (selection menu, accessibility button, the "Try it" card on Home) pass `check()` an `origin`, and the first two the package the text came from. Only the first two are recorded (`Origin.recorded`, decided in `CheckHistory.open`): "Try it" is for trying the app, not writing, so it opens no session, looks up no kept answer and writes nothing.

```
CheckViewModel ──▶ CheckHistory ──▶ SessionRecording
                       │              (pure Kotlin, tested)
                       └─writes─▶ HistoryStore ◀── SqliteHistoryStore (history/)
```

- `SessionRecording` turns what happens on one card into records: one session and its suggestions. It's pure Kotlin with no Android or storage in it, so the mapping (which suggestion ended up accepted, undone, ignored, retired, copied) is unit-tested like the edit engine. One instance per `check()`, which is one per card: nothing the writer does while it's up starts another.
- `CheckHistory` is what the view model talks to. It opens the session, queues the writes, reads whether history is on before each one, and races the model against a kept answer (see *Reusing a kept answer*). Its failures are logged and never reach the card.
- What a check runs with (provider, model, options, native language, prompt hash) is read once per run into `CheckContext`, the one place that lists it. Each attempt records it (`CheckContext.stored`, tokens) with the language it asked for, the session has the latest attempt's as `SessionSettings`, the reuse lookup matches an attempt's with the text and requested language, and the overlay's card compares it to tell whether Settings changed anything (`CheckViewModel.recheckIfStale`).
- `HistoryStore` is an interface with one implementation, `SqliteHistoryStore`, on the framework's SQLite with hand-written SQL (`HistorySchema`). If storage changes (SQLDelight for an iOS port, a server), nothing above it does.
- `App` has an application-wide coroutine scope. The close-of-card write happens as the view model is being cleared, when `viewModelScope` is already cancelled, so it needs a scope that outlives the screen. Writes are queued on it in order.
- The selection menu's card is often the only thing of the app running, and a process with nothing running is the first one the system kills. So `CheckActivity.finish()` waits for the close to be written before the activity goes, blocking for 500 ms at most (`CheckHistory.awaitWrites`). The close is one short transaction, normally a few milliseconds; the limit only bites when the database is slow to open, and then the write goes on alone. A WorkManager job would be sturdier but is a new dependency and a second write path for a few milliseconds of risk.

### What the recording sees

| Event | From | Recorded as |
|---|---|---|
| `check(text, language, origin, hostApp)` | menu, button | a new **session**, saved immediately with `closedAt = null`, unless history is off or the text is over the 3,000-character limit (the check refuses it anyway). From "Try it": nothing, ever |
| verdict arrives, or fails | `run()` | an **attempt** appended to the session (the model's answer as it came, settled answers, failure, `reusedFrom` when a kept answer was shown, and the language and settings it ran with); the session's language and settings become the attempt's |
| re-check with other settings | `run()` (`recheckIfStale`) | nothing new: the same session, whose next attempt records the new provider, model, options, prompt or native language. Accepted changes carry over as on any re-check |
| another language picked on the card | `changeLanguage()` | the same session. The card starts over in that language (nothing accepted or answered carries over: it's another review), so the revision on offer is `superseded` at once, whatever the next attempt brings; the next attempt records the new language, and the session ends with the last one picked. Raced against a kept answer like a first attempt |
| `accept`, `acceptAll`, `undo` | card | the new revision, remembered in memory (`everAccepted`) to tell *undone* from *ignored* later; a change to a revision other than the decided one (a late report from an earlier attempt's card) is ignored |
| Copy of a section's preview | card | remembered in memory: what that copy contained, exactly as the preview rendered it |
| close: `dismiss()`, a new `check()`, or `onCleared()` | any host | the session is **closed**: final text, outcome, and the suggestion rows with their decisions |

Closing is the one subtle part. The hosts close in different ways, but all of them end in `dismiss()`, a new `check()` or `onCleared()` on the view model. Nothing is written on every tap: one write at open, one per attempt, one transaction at close. A close replaces the session's suggestion rows (it deletes any there and inserts the final set), so closing twice never doubles them.

### Decisions, defined

Each suggestion ends the session with exactly one decision. The suggestions decided at the close are those of the *decided revision*: the last one an attempt offered. Suggestions of earlier revisions are `superseded`.

| Decision | Meaning |
|---|---|
| `accepted` | applied in the final text, which only exists when `finalText` went back to the app (and, if a fix, not replaced by an accepted rewording) |
| `undone` | accepted at some point, not applied at the end |
| `retired` | a fix overtaken by an accepted rewording (the rewording includes it) |
| `copied` | the writer copied that section's preview and the suggestion was in it: accepted changes and that kind's remaining suggestions, as the preview rendered them. A fix the copied preview left out because a rewording in it replaced it is `retired`, not copied |
| `ignored` | still on offer when the card closed |
| `superseded` | from an earlier revision; a re-check (answered assumption, retry, new settings) that offered a revision of its own replaced it, or another language was picked on the card |

Rules at the close, as `SessionRecording` applies them:

- **Something went back to the app** (`finalText` set): applied, then retired, then copied, then undone, then ignored; the first that holds.
- **Nothing went back**: nothing is `accepted`. What was copied is `copied`; a fix inside an accepted rewording that was copied is `retired`; whatever else was accepted at some point is `undone`; the rest `ignored`.
- **A failed or unclear attempt keeps the previous revision decided.** The card keeps that revision's accepted changes and applies them if the writer replaces the text, so only an attempt that offers a revision makes the previous one's suggestions `superseded`. The exception is another language: the card drops the old review then, so its suggestions are `superseded` when the language changes.
- **What was accepted and copied is tracked per revision.** A re-check carries identical accepted edits over as new edits, and they count as accepted in the new revision.
- **`reusedFrom`**: an attempt answered from a kept verdict records the session it came from; its suggestions are recorded like any other attempt's.

Session outcome is one of `applied` (`finalText` set: changes went back to the app), `copied` (nothing applied, something copied), `failed` (nothing applied or copied, and the last attempt failed), `none` (closed with nothing), `abandoned` (never closed: the process died; marked on next start). Applied wins over copied, copied over failed. `outcome` is null while the session is open. The `status` column holds what the last successful attempt found (`ok`, `unclear`, `wrong_language`), so a check that came back unclear is told apart from one that was fine and had nothing to suggest.

These are the facts the insight feature needs: *this writer makes agreement mistakes in Catalan and takes the fix 90% of the time; the model keeps suggesting "quedar" and the writer ignores it.*

## Data model

Two tables. Rows are immutable once the session closes. Everything is designed so a row can be uploaded as-is later.

**Every row has:** `id` (client-generated UUID, never an autoincrement), `createdAt` / `updatedAt` (epoch ms, UTC), `deletedAt` (reserved for sync; always null today, see *Sync-readiness*), `deviceId` (a random UUID made once per install), `schema` (integer, the shape of this row).

**Stored values.** Enums are stored as fixed lowercase tokens, spelled out on each enum's constants (`token`, with one `codec.Tokens` per enum) rather than derived from constant names, the same in the database, the export, settings and the prompt. Languages are stored by ISO 639-1 code (`Language.code`), failures by the reason's token. A token this build doesn't know (written by a newer one) reads as null in the app and never crashes (an unknown origin too: Recent then shows no source); a suggestion with an unknown kind or decision is left out of the page and the counts. The export doesn't decode at all, so unknown tokens leave as they were stored.

| Field | Tokens |
|---|---|
| `origin` | `menu` · `button` (`tester`, the "Try it" card, isn't recorded since version 4; the token stays so old rows can be found and deleted) |
| `outcome` | `applied` · `copied` · `none` · `failed` · `abandoned` |
| `status` | `ok` · `unclear` · `wrong_language` |
| suggestion `kind` | `fix` · `natural` |
| suggestion `decision` | `accepted` · `undone` · `retired` · `copied` · `ignored` · `superseded` |
| `punctuation` | `strict` · `moderate` · `casual` |
| `judgments` | `both` · `fix` · `naturalize` |
| `provider` | `gemini` · `openai` |

### `sessions`, one per time the card opens (whatever changes while it's up)

| Column | Notes |
|---|---|
| `startedAt`, `closedAt` | `closedAt` null while open |
| `origin` | see above |
| `hostApp` | package name of the app the text came from (menu: the calling package; button: the field's package); null when unknown |
| `requestedLanguage` | the ISO 639-1 code of the language the latest attempt asked for (`ca`, `es`): the last one picked on the card; null for auto. Versions 1 and 2 kept its English name, rewritten to the code by the upgrade |
| `nativeLanguage` | the writer's own language, which the meaning and reasons were asked in; null for sessions recorded before version 2 |
| `text` | what was checked |
| `textHash` | short hash, for "same message checked again" without comparing texts |
| `finalText` | what went back to the app; null when nothing was applied |
| `outcome`, `status` | see above |
| `punctuation`, `judgments` | the settings in force, as tokens; they change what gets suggested |
| `provider`, `model`, `promptHash`, `appVersion` | so verdicts from different models and prompt versions aren't compared as if equal. Like `punctuation`, `judgments`, `nativeLanguage` and `requestedLanguage`, the latest attempt's: they can change while the card is up, and each attempt keeps its own |
| `attempts` | JSON: `[{at, settled: [{about, answer}], raw, failure?, failureDetail?, reusedFrom?, requestedLanguage?, nativeLanguage?, punctuation?, judgments?, provider?, model?, promptHash?}]`. The last seven are what the attempt ran with (`SessionRecord.reuseKeyOf`), named as the session's columns; rows from before version 4 got the session's from the upgrade, and a row of schema below 4 is read as running every attempt with the session's. The audit trail; never queried, kept so anything can be re-derived. `raw` is the model's answer text exactly as it came back, before it was read: fields the app doesn't know yet are in it, and it's kept as well when it couldn't be read (a `BadResponse` failure); null when no answer came. `failure` is the `CheckFailure.Reason`'s token (`offline`, `bad_response`, …; versions 1 and 2 kept the constant name, rewritten by the upgrade); the language the model judged the text as is in the raw answer, not a column. Sessions recorded before version 2 hold the verdict as the app had read and re-encoded it (unknown fields dropped), moved to `raw` by the upgrade |
| `meaning` | from the last successful attempt |

### `suggestions`, one per edit the writer saw

| Column | Notes |
|---|---|
| `sessionId` | FK |
| `attempt` | index into the session's attempts |
| `kind` | see above |
| `start`, `end`, `fromText`, `toText`, `why` | as the card showed it, positions in the session's `text` |
| `decision`, `decidedAt` | see *Decisions, defined* |

### Versions and upgrades

The database has a version (`HistorySchema.VERSION`, for `SQLiteOpenHelper`) and every row a `schema` (its shape, as it would travel). Both are 4:

- **1**, the first history builds: enums stored by constant name (`Accepted`, `Gemini`), the answer under `verdict`, no `nativeLanguage`; the earliest had no `status` column either (builds after the token change added it without changing the version).
- **2**: tokens, `raw`, `nativeLanguage`. The upgrade from 1 adds the missing columns, rewrites every name-encoded value to its token (so Recent's counts and the reuse lookup, which compare tokens, see old rows), moves each attempt's `verdict` to `raw`, and sets `schema` to 2, in the one transaction the framework opens for it.
- **3**: `requestedLanguage` as a language code, an attempt's `failure` as a token. The upgrade from 2 (and from 1, after the steps above) rewrites the language names and failure names it knows; the names it spells out are frozen, since those versions are done, and a test holds them to the enums.
- **4**: one session per card, and nothing from "Try it". The upgrade from 3 (and from 1 and 2, after the steps above) deletes the sessions with origin `tester` and their suggestions (explicitly, though the foreign key cascades too, so the delete doesn't depend on the connection's foreign-key setting; `secure_delete` overwrites the freed pages), and gives each attempt the session's language and settings, which every attempt of those versions ran with.

A version with no way up (none exists; a bug or a hand-edited file) isn't refused, which would turn history off for good with only a log line: the tables are dropped and made afresh, and that is logged. A downgrade (an older build installed over a newer one) is still refused by the framework; history is then off until the newer build is back. Each new version adds its step to `HistorySchema.upgrade` and a test that upgrades a database made with the previous version's statements, as `SqliteHistoryStoreTest` does for version 1 (from commit 7237d59), version 2 and version 3.

Why not an event log? An append-only log of every tap is the purest shape for sync and would capture undo trajectories exactly, but it needs projection code from day one to show anything, and the insight feature would be a projection too. Two tables that a human can read in a SQLite browser, with the raw verdicts kept as JSON for anything we didn't think of, is the smaller debt. The `attempts` JSON is the escape hatch: if a future question needs something the columns don't have, it's in there.

Why not one JSON document per session? It would be simplest to write, but `suggestions` is the table the insight feature aggregates over (by language, decision, date, text), and that wants real columns and an index, not `json_extract`.

### Sync-readiness, concretely

What's done now so a server later is additive:

- UUIDs and `deviceId`: rows from two phones merge without collisions.
- `updatedAt`: "everything changed since last sync" is one query. It has no index yet; the sync that runs that query adds one.
- `schema` and `promptHash` versions on the rows: the server never has to guess how to read an old row.
- The row classes are `@Serializable` Kotlin with the columns' names and tokens: the wire format is the storage format. The export writes each row as it is stored, column by column (a `SessionDetail`'s shape per line), without decoding it, so nothing is lost or altered on the way out. Versioned, so that coupling is a feature, not a trap.
- Nothing in a row depends on local state (no int ids, no references to settings, no Kotlin constant names: languages are codes, everything enumerated a token).

What isn't done, and what changed from the first plan:

- **Deletes are hard deletes.** Delete-one and Clear remove the rows (suggestions go by cascade), because the writer asked for the text to be gone and nothing exists yet to carry a tombstone. The database runs with `secure_delete`, so deleted rows are overwritten rather than left in free pages; a delete checkpoints the write-ahead log, and Clear also vacuums, so no copy of the text is left in the files. The `deletedAt` columns exist and every query filters on them, but nothing sets them. So deletions do **not** travel today: when sync is built it needs either soft deletes from then on or a record of deleted ids, and rows deleted before that can't be told apart from rows never uploaded.
- No `syncState` table, uploader, auth or conflict rules. When it comes: last-write-wins by `updatedAt`; with one writer per device that's never wrong in practice.

## Prompt changes (prerequisite, done)

The verdict has a **`language`** field: the language the model judged the text as, as a name ("Catalan", "Spanish", "French", …); empty when the text is unclear, and the language it actually found when `status` is `wrong_language`. Auto-detected checks can be grouped by language from it (it's in the raw verdict kept in `attempts`), and the card says what a wrong-language text looks like it is. The prompt's examples and the eval script's cases carry it.

No mistake categories are assigned at check time. The insight feature will hand a model the raw edits (from, to, why) across the whole history and let it find the patterns; at this scale that's one or two calls, it's consistent across all of history, and the structure (if any) is chosen with data in hand.

## UX

### Where history lives

The first instinct is a small history button on the card, next to the language picker and the settings gear, opening the list inside the card. The argument for it is real: the card is where the writer is 95% of the time, and a past check should look the way it looked the first time. The second half of that is right and the first half is a trap.

**The card is a moment, not a place.** It lives for a few seconds between "did I write this right" and send, over someone else's app, usually with the keyboard up. Everything on it competes with the two controls that matter (Replace, Done). History is reflective: you read it when you're not writing to anyone. Putting a diary behind a button on the thing that has to feel like spellcheck adds a third job to a surface that's good because it has one.

Two smaller problems: that header (picker + gear) only exists on the accessibility overlay. The selection-menu card, which is most checks, has no header, so a history button there means adding chrome to the fastest path. And there's no action to take on a past check from inside the card except reading it.

**What the instinct gets right:** a past check should be rendered with the card's own content (`ReviewContent`, the highlights, the reasons, what was taken), not as a settings-style form. The tester already proves the card's content works inside a full-screen page. So the detail view reuses the component, wherever it lives.

**History should reach the card as data, never as navigation.** The in-the-moment uses of history don't need a button:

- *Instant re-open.* Checking the same text again within a few minutes (closed by accident, app lost the card) reuses the stored verdict: no model call, no wait. Free, invisible, and the first thing that makes the recording pay. See *Reusing a kept answer*.
- *"You've had this fix before."* A fix on the card can carry one quiet line ("4th time this month"). That's the insight feature arriving in-situ. Later; noted here so the data supports it (it does: `fromText`, `toText`, `decision`).

### The launcher screen becomes Home

Today the launcher screen is Settings with a tester at the bottom. The tester and history are the same thing, checks, at different times: "check something now" above "what you checked before". So:

**Home**
> lingu·ize                                   ⚙
> [ text box ]  Linguize ▾
> (the result card, when a check is running)
> Recent
> ▸ 9 Oct · WhatsApp · "Hola bebé, estoy en casa…" · 3 fixes · 2 taken
> ▸ 9 Oct · Telegram · "Com estàs amb la pluja?" · looks good
> …

- Tap a row: the check opens on its own page, rendered with the card's content, read-only, with Copy. Delete from there, or swipe the row away; either deletes for good once the moment to undo has passed. A swiped row waiting for Undo is noted on disk, so if the process ends in that moment the next Home deletes it.
- Insights, later, are a strip between the text box and the list. They don't need a new screen either.
- The gear (top right) opens **Settings**: the existing Checking, Model and Apps-without-the-menu sections, plus a History section (toggle, count, Clear, Export). Browsing lives on Home; controls live in Settings.
- Three screens of one activity (Home, Settings, and a past check's page), a `BackHandler` and a `Screen` state. No navigation library.
- The overlay's gear keeps opening the same activity.

Design debt this removes: the app's most interesting content was heading for a settings section, and the launcher screen was going to need splitting anyway when insights arrived. Doing it now is one move instead of two, and it's mostly moving sections that already exist.

### If a compact entry is ever wanted

The glyph is Material's `history`: a clock with a counter-clockwise arrow. It's the one shape browsers and Google's apps all use for "what I did before", so it reads without a label; a plain clock means scheduling. The recommended design doesn't need it, because Home *is* history. If it's wanted on the overlay header later, it sits between the picker and the gear and opens Home.

### Settings · History

> **History**
> ☑ Remember what I check
> Kept on this phone only, so Linguize can later show you the mistakes you tend to make. Turning this off keeps what's already here.
> 312 checks since 9 Oct · **Clear** · **Export**

- *Remember* is on by default. Off stops recording; it doesn't delete.
- The count and the date are of what Recent can show: closed sessions. A card still open isn't counted until it closes.
- *Clear* asks once, then deletes everything.
- *Export* writes a JSON Lines file (one session per line, suggestions nested, each row as stored) and offers it through the share sheet. It's the sync payload in disguise, and the way to look at the raw data in a notebook. The file holds every kept text, so it doesn't linger in the cache: it's removed a few minutes after it's offered (long enough for the app it went to to read it), on the next visit to Settings if the process ended first, on Clear, on any delete, and at once if the export failed. An export that finishes after Settings was left isn't offered later.

### Privacy, stated plainly

- Nothing leaves the phone. The data extraction rules already exclude the app from backups and device transfer, so history doesn't land in Google's cloud.
- Text checked in the app's own "Try it" card isn't recorded: it's for trying Linguize, not writing, and would only skew the insights. Everything checked in another app is.
- Failed checks are recorded (half the reliability story). They are not hidden from Recent yet; a failed row shows in the error colour.
- Read-only selections are recorded; their suggestions can only end as `copied` or `ignored`.
- Column encryption with the existing Keystore key is possible and costs: no SQL over text, slower lists, more code, and a server couldn't read it. The sandbox plus backup exclusion is the right level while the app is personal or among friends. Before friends install: the History section's wording is the consent, and recording should be shown once (a line on Home the first time), not assumed. That line isn't built; Recent only says so when history is off.

## While a card is open

A session is saved when the card opens and written for the last time when it closes, and the writer can clear, delete or turn off history in between (from Settings or Recent while a card is up). The rules:

- **Clear, or delete of that session**: final. `SqliteHistoryStore` remembers which sessions it saved in this process and which were cleared or deleted, and, in the same transaction as the write, drops a later save or close of a session that was open when the clear ran, or of a deleted one. The row doesn't come back. This goes by ids, not times: the wall clock can move backwards, and a comparison of start and clear times would then drop new sessions.
- **Turn off**: `enabled` is read before every write. Once it's off, the open session stops being recorded: nothing more is written for it, its close included, and turning history on again while the card is up doesn't start recording it again (a language change on that card opens no session; nor does one on a card opened with history off). Its row stays open (invisible in Recent, which lists closed sessions only) and is marked `abandoned` when the app next starts.
- **A closed session is final**: a save arriving after the close is dropped. The save is an update in place, never a replace, which would delete the row and cascade to its suggestions.
- A session still open when the process ended is marked `abandoned` at the next start: every open row that this process didn't open (the store knows the ids it saved), whatever its times say.

## Reusing a kept answer

On a fresh check, `CheckHistory.firstAttempt` starts the model request first, before any history work. Then it opens the session and, on the application scope, looks for a kept verdict while the request runs. Whichever answers first wins:

- the kept verdict arrives first: it is turned into a result, shown, and the request is cancelled; the attempt records `reusedFrom`;
- the model answers first (or the lookup finds nothing, or fails): the model's answer is shown at once and the lookup is cancelled. The lookup can never add latency.

Another language picked on the card (`CheckHistory.languageChanged`) races the same way, for the text in the new language, in the same session.

Matching is per attempt, since a session's language and settings can change while its card is up. A session is a candidate when it has the same text hash and its latest attempt the same requested language, native language, punctuation, judgments, provider, model and prompt hash (the query, on the session's columns); started within 10 minutes of the new check; is closed and not `failed`; and its last attempt succeeded. What's shown again is its *decided attempt*'s answer, with that attempt's settled answers, and only when that attempt itself ran with the same language and settings (`SessionDetail.keptFor`, checked by the store and again by `CheckHistory`): a card that ended on an answer made before its settings changed is never shown under the new ones. The newest candidate that qualifies wins. Nothing is looked up when history is off or the text isn't recorded ("Try it").

**The decided attempt** (`SessionDetail.decidedAttempt`) is the one place that says which attempt a session's card ended on: the attempt the session's decided suggestions (those not `superseded`) came from, or else the last attempt that succeeded. Reuse, the past check's page and its settled answers all use it.

**A past check's page** shows the decided attempt's answer, read again for what only it holds (meaning, assumptions), with what the writer took taken from the suggestion rows: when today's engine finds exactly the recorded suggestions, its edits are used with the `accepted` ones accepted; when it doesn't (the engine changed since), the card is built from the rows alone. Either way a change to the engine can't lose what was taken.

## Requirements

### Must have (v1), all built

- [x] `language` in the verdict schema, prompt, examples and eval.
- [x] `HistoryStore` interface; SQLite implementation with `sessions` and `suggestions`; DB created lazily; all I/O off the main thread.
- [x] `SessionRecording` (pure Kotlin) with tests for the decisions and outcomes in the tables above, including: undo after accept, rewording retiring a fix, Copy, re-check superseding, a failed attempt, close with nothing.
- [x] `CheckViewModel` reports to `CheckHistory`; `check()` takes an `origin`; the hosts pass it.
- [x] Open sessions found on process start are marked `abandoned`.
- [x] Launcher screen split into Home (text box, result card, Recent list) and Settings (gear); `BackHandler`, no nav library.
- [x] Recent list on Home, newest first (the latest 50); tap opens the check rendered with the card's content; delete one.
- [x] Settings · History section: toggle, count, Clear, Export (JSON Lines via the share sheet).
- [x] Same text checked again within 10 minutes reuses the stored verdict instead of calling the model.
- [x] Card latency unchanged: the request leaves before any history work.
- [x] `App` has an application scope; the close write uses it.

Acceptance, the ones worth spelling out:

- Given history is on, when a check starts, then a session row exists before the model answers, even if the app is killed a second later.
- Given a fix was accepted and a rewording covering it was then accepted, when the card closes, then the fix is `retired` and the rewording `accepted`, and "changes applied" on the card still reads 1.
- Given the writer taps Replace then Undo then closes, then the suggestion is `undone`, not `ignored`.
- Given history is off, when a check runs, then no row is written, and the count in settings doesn't change.
- Given a check in the "Try it" card, then no row is written and nothing is looked up, whatever is done on the card.
- Given a popup whose language is toggled several times and whose settings change, when it closes, then there is exactly one session, closed once, with the last language picked and one attempt per check, each with the language and settings it ran with.
- Given 2,000 sessions, when settings opens, then it opens as fast as today (the count is one indexed query, observed as a Flow).

### Should have (v1.5)

- [x] `hostApp` recorded (menu: `callingPackage`; button: the field's package) and shown in Recent.
- [ ] Recent hides failed checks by default, with a way to show them.

### Later (designed for, not built)

- Insights: a model reads the kept edits and their reasons and names the patterns, shown as a strip on Home; "you've had this fix before" on the card.
- Sync: `syncState` table, uploader, opt-in separate from this one.
- Encrypt text columns before a public release.

## Tech-debt ledger

Taken on, deliberately:

- **Hand-written SQL on the framework's SQLite** (`SQLiteOpenHelper`) instead of Room. No new dependency and no code generation (a KSP version can't be verified against this toolchain without CI round-trips), at the cost of writing two tables' worth of SQL and Flows by hand. A test checks every record field has a column. Room or SQLDelight remain an easy swap behind `HistoryStore`.
- **An application coroutine scope.** One new concept; it exists for writes that must outlive a screen (the close write, Clear, a committed delete) and nothing else should grow on it.
- **`origin` on `check()`.** One more parameter on the view model's entry point.

Avoided:

- No event-sourcing machinery, no DI framework, no navigation library, no encryption library, no sync scaffolding, no analytics.
- No change to the card's behaviour. `core/` gained only small things the recording reads (`Verdict.language`, `Revision.isApplied` and `isRetired`, `WrongLanguage.found`); the edit engine doesn't know history exists.
- No second copy of the data model for the wire: the row classes are the payload.


## Open questions

- Home-first (above) or the original idea (a settings section plus a history button on the card)? The spec argues for Home-first; it's the founder's call.
- Whether the raw verdict in `attempts` needs a size cap (a 3,000-character text twice over plus lists is ~15 KB; fine, but confirm).
- Soft deletes or a deleted-ids record before sync (see *Sync-readiness*).

## Phasing

0. Prompt: `language`, examples, eval. Ship on its own; it's visible nowhere and de-risks the rest.
1. Record: store, recording, view-model hook; Home/Settings split with the Recent list and the History section; export; instant re-open; host app in Recent. Built.
2. Filters (failed checks); a first-time line about recording.
3. Insights: a separate spec, written once there are a few hundred fixes to look at.
