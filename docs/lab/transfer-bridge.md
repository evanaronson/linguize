# Transfer bridge (temporary)

> **TEMPORARY — delete after the owner has migrated.** This bridge exists only to move the
> owner's data from Linguize X Claude into Linguize once `feature/bonanza` is merged and the
> application id is back to `com.evanaronson.linguize`. When the owner confirms the data is in
> Linguize, remove everything listed under [Removing it](#removing-it) in one change.

## Why

Linguize X Claude (`com.evanaronson.linguize.xclaude`) installs beside Linguize, and Android
gives each app its own private storage. The owner used the experimental app for real. After the
merge, Linguize runs this branch's code under its own id, and this bridge pulls the experimental
app's data in once. Both apps are signed by CI with the same key.

## How it works

- **Exporter** (`transfer/LabTransferProvider`, `transfer/TransferExporter`). A content provider
  at `${applicationId}.labtransfer`, exported but guarded by the fixed permission
  `com.evanaronson.linguize.permission.LAB_TRANSFER` with `protectionLevel="signature"`. Both
  builds declare and request it; Android grants it only to an app with the same signer. The
  provider checks it again and answers only `openFile(content://…/bundle, "r")`: it streams one
  snapshot through a pipe. It never queries, inserts, updates or deletes, and reads without
  changing anything: databases through their stores' read snapshots (a database that doesn't
  exist isn't opened, so none is made), files as bytes, preferences through `getAll`. Stores that
  migrate or repair a file when loaded (Notes' pad import, Settings' migrations) are not used.
- **Bundle** (`transfer/TransferBundle`). JSON Lines: a header with the format and History
  database version, one line per History session with its suggestions, then chat, time, files,
  preferences, and an `end` line. Rows travel exactly as stored. A bundle without its end, or
  of another format, is refused whole and nothing is merged.
- **Importer** (`transfer/TransferImporter`, `transfer/LabTransfer`). Settings shows
  "Bring in data from Linguize X Claude" only when that package is installed and this app is
  not it (`<queries>` names the package). It asks first, reads the whole bundle, then merges
  each kind on its own, and shows what came in, what was here already, what stayed behind and
  what failed. If the provider is missing (an older Linguize X Claude) or refuses, it asks the
  owner to install the newest Linguize X Claude build. The import runs on the app scope, so
  leaving Settings doesn't stop it halfway.

## What moves, and how it merges

Additive only: nothing in Linguize is replaced or deleted. Running it again adds nothing.

| Store | Where | Rule |
|---|---|---|
| History sessions and suggestions | `history.db` (`SqliteHistoryStore`, schema 8) | Insert sessions whose id isn't here, with their suggestions, in one transaction (`insertIfAbsent`). An existing session and its suggestions are never touched. Skipped: open (unfinished) sessions, rows with `deletedAt`, sessions waiting in History's Undo (`recent` preferences) and sessions of chats whose deletion is pending in the source. A check deleted there stays deleted. All History stays behind when History is off here, or when the two databases have different versions. No schema change. |
| Chat conversations and messages | `lab_chat` (`ChatStore`) | One transaction: conversations whose id isn't here, then messages whose id isn't here and whose conversation is. A conversation here keeps its title and settings; its `updated_at` only moves forward for a message added to it. A conversation deleted here with cleanup pending is never brought back. Message `session_id`s are copied as they are, and History keeps its ids, so links stay consistent. |
| Time spent | `lab_time_spent` (`TimeStore`) | Insert records for sessions that came in (or were already here) and have no record here (`insertIfAbsent`, one transaction). Never replaced, so never counted twice. Stays behind with History. |
| Notes | `lab_write.json`, or the Writing pad's `lab_writing_pad.json` when Notes was never opened there | Add non-blank notes whose id isn't here (`WriteDraftStore.restore`). The open note here stays open. |
| What Linguize knows | `lab_memory.json` (`MemoryStore`) | Add facts whose id isn't here, with the dates of the sources they cite; this app's dates and its learning switch win. Facts learned only from a chat whose deletion is pending in the source are forgotten first. |
| Phrasebook | `lab_phrasebook.json` | Add phrases whose id isn't here and whose words aren't saved here (`PhrasebookStore.restore`). |
| Teach me lessons | `lab_teach_me.json` (`LessonStore`) | Add lessons whose key isn't here, up to the 200 Teach me keeps; none here is pushed out (`addIfAbsent`). Refuses to write over a lessons file here it can't read. |
| Imported decks | `lab_vocab_gaps/decks/` (`VocabGapsStore`) | Add decks whose id isn't here, with their words, keeping the id (`adopt`). |
| Your patterns explanation | `lab_patterns` preferences | Fill only when there is none here, and only when History came in. |
| Your languages | `lab_lang_generic` | Union: this app's list in its order (its seed if never edited), then the languages only the source has. |
| Per-app voice | `lab_app_voice` | Add rules for apps that have none here. |
| Settings | `settings` | Fill `provider`, `popupPosition`, `punctuation`, `judgments` and `model.*` only when never set here. Writing profiles: add the source's profile for each language with no profile here; never edit one here (all of them when none was ever stored). |
| Chat and scope choices | `lab_chat`, `lab_language_scope` | Fill only when never set here. "Start on" counts as set when either it or the older "Open to chat" switch is set. |

Derived data refreshes from the stores' own signals: a History write bumps its revision, which
the shared evidence reader, the corpus, Words, Conjugations and Chat targets follow. Memory and
the Phrasebook publish their new state. Settings refreshes after the import. Screens that read
on open (Notes list, Chat list, Words decks) show the new data the next time they load.

## What never moves

- API keys (`keys` preferences, sealed with this install's Keystore key). The summary says to add them in Settings.
- History's pending deletions (`recent` preferences); only their ids are read, to leave those checks out.
- The install's `deviceId`, the History switch and its epoch: they describe this install.
- The selection-menu entries (component states in the package manager), the export cache, files set aside as `.unreadable`, and Words' last selection.
- Any preference key not listed above, including ones a newer build may add.

## Limits

- Re-running after deleting something that came in brings it back, except a chat whose cleanup is still pending.
- Only the merged result was tested, in JVM/Robolectric tests with two sets of stores in one process (`LabTransferTest`). The cross-app path (two installs, the signature permission, package visibility, the pipe between processes) has not run on a device yet.

## Removing it

Delete:

- `app/src/main/java/com/evanaronson/linguize/transfer/` (the whole package)
- `app/src/main/res/values/strings_lab_transfer.xml`
- `app/src/test/java/com/evanaronson/linguize/transfer/`
- this file, and its row in [README.md](README.md)

Remove the hooks, each marked `TEMPORARY: lab transfer bridge`:

- `app/src/main/AndroidManifest.xml`: the `<permission>`, its `<uses-permission>`, the `<package>` in `<queries>`, and the `<provider>`.
- `ui/home/HomeActivity.kt`: the `LabTransferSection` import and call in Settings' `aboutSection`.
- `App.kt`: `lessonStore` (inline it back into `teacher`, or keep it).
- `history/SqliteHistoryStore.kt`: `transferRows`, `insertIfAbsent`, `TransferSession`, the required-column lists, `rawRow`, `insertRaw`, and the `Double` case in `bindAll`.
- `history/HistoryStore.kt`: `Forgotten.transferable`.
- `data/chat/ChatStore.kt`: `transferRows`, `insertIfAbsent`, `ChatTransferRows`, `ChatTransferResult`, the column lists, `rawRows` and `contentValues`.
- `data/time/TimeStore.kt`: `insertIfAbsent`.
- `data/teachme/LessonStore.kt`: `addIfAbsent` and `LessonTransfer`.
- `data/vocabgaps/VocabGapsStore.kt`: `adopt`.
- `scripts/check_architecture.py`: the `transfer` layer and `"transfer"` in `ui`'s set.
- `docs/architecture.md`: the `transfer` row.
