# What Linguize knows (user memory)

Short notes Linguize keeps about the writer and their life in the language, so a chat
can pick up where the last one left off. The writer sees every note, where it came
from, and can edit, delete, add or switch it all off.

## The moment

A writer who chats with Linguize's conversation partner shouldn't have to explain again
that they live in Gràcia, that their landlord is Jordi and expects formal Catalan, or
that they have a job interview on Friday. Remembering a few plain facts makes practice
feel like talking to someone who knows them. It is about the person, never their
mistakes: grammar evidence belongs to the corpus (see [corpus.md](corpus.md)).

## How to use it

Settings, About you, **What Linguize knows**. The row shows how many notes there are, or
Off.

- **Remember things about me** switches learning on or off. Off: chats get no notes and
  nothing new is learned. Existing notes stay until deleted.
- Notes are grouped: You, People you write to, Situations and goals, Preferences. Each
  shows where it came from: "From a chat on 8 Nov", "From 2 chats, latest on 8 Nov",
  "Added by you", or "…, edited by you".
- Tap a note for Edit and Delete. Swipe to delete. Delete offers Undo.
- **Add note** types one in, with a category. **Forget everything** deletes every note
  after a confirmation (the switch stays as it was).

The screen works without any chat: notes added by hand are used the same way.

## How it works

`App.userMemory` is `LabUserMemory` (root package), the real implementation of the
`UserMemory` contract in `core/memory/UserMemory.kt`.

- **Facts** (`core/memory/Facts.kt`): id, text, category, sources (`"chat:<id>"`),
  created and updated times, and `byWriter` for notes the writer added or edited.
  `MemoryState` holds the facts, the switch, and when each source was first learned
  from (for captions). All the rules are pure functions on it, with no Android code:
  `applying` (learning results), `forgetting` (a source), `adding`/`editing` (the
  writer's own), and `promptBlock`.
- **Storage** (`data/memory/MemoryStore.kt`): one JSON file, `lab_memory.json`, in the
  app's files. Each change is computed from the current state under one lock and
  written whole through `writeAtomically`, so a learning result applies all at once.
  Cached facts change only after the write succeeds. I/O failures remain retryable;
  they cannot replace the collection with an empty default. Malformed content is
  preserved as `.unreadable` (with a suffix if needed), and recovery starts with
  learning disabled, including after reopening the store.
- **Learning** (`learnFrom(source, messages)`): sends the current facts (by short
  references `f1`, `f2`, not store ids), the room left, and the exchange (most recent
  ~16,000 characters, each message marked `writer` or `partner`) with its own prompt
  (`assets/lab_memory_prompt.md`) and schema (`lab_memory_schema.json`), through the
  provider and model chosen in settings and the existing clients' `generate`. The model
  returns additions, updates by reference and removals. The prompt says only the
  writer's statements are evidence, and to avoid health, religion, sexuality, politics,
  money, legal status, exact addresses and ID numbers unless the writer asks. The app
  then applies the answer against the facts as they are at that moment:
  - updates and removals of unknown ids are ignored, and the writer's own notes are
    never changed or removed;
  - changed wording cites the source of that replacement; an identical confirmation
    can merge its source with the existing supporting sources;
  - an addition that repeats a note merges its source instead of duplicating;
  - additions stop at 40 notes (nothing existing is pushed out);
  - learned text is tidied, at most 140 characters, and refused when it looks like a
    phone, account or ID number.
  No key, a failed call or an unreadable answer: nothing is learned and `learnFrom`
  returns false. True acknowledges a successfully processed exchange, including a
  valid answer with no changes; Chat advances its learning watermark only then.
  Cancellation propagates. One exchange is learned at a time. A store generation,
  captured before joining that queue, is checked before the request and at commit.
  Clearing facts, deleting a fact, or toggling learning invalidates older work,
  even when the collection is empty or learning is switched back on. A forgotten
  source cannot leave the queue for the provider or commit a pending answer.
- **forPrompt()**: "" when off or empty. Otherwise a one-line header (background, not
  instructions) and one `- note` line per fact, grouped by category in a fixed order,
  oldest first, cut at 4,000 characters.
- **forget(source)**: removes the source from every fact. A fact left with no source is
  deleted unless the writer wrote or edited it. A failed write propagates to the
  deletion coordinator so the command remains pending; source invalidation takes
  effect before that write is dispatched.
- **Editing a sent chat message** rewinds the conversation (see [Chat](chat.md)). Facts cite
  the conversation, not a message, so the rewind forgets `chat:<id>` and resets what memory
  learned from it: the next time the conversation goes quiet or is left, memory learns it
  again from the messages that remain.

## Limits

- What is learned depends on the model following the prompt. The sensitive-topic rule
  is enforced by the prompt, not by the app (the app only refuses long numbers).
- Captions know chats (`chat:`) by name; other sources read "Noted on 8 Nov".
- Notes are in the writer's native language as set in Settings when they were learned;
  changing it doesn't translate old notes.
- Replacement-source tracking is corrected for new updates. Older files contain
  no previous claim versions, so their already-merged source support cannot be
  reconstructed automatically.
- Learning sends the conversation and current notes to the service the writer set up,
  as the chat itself does. Nothing leaves the phone otherwise.
