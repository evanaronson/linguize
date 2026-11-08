# Chat

Linguize opens to a conversation. A friendly native speaker says hello in the language
you last used, and you just talk. Before each message goes, Linguize checks it on the same
card as everywhere else, so the partner never has to comment on your grammar.

## The moment

Most of the app is settings and tools you reach from other apps. Chat gives the person a
reason to write when nobody is waiting for a message: low stakes, every day, in their own
words. It keeps the product's promise of checking before sending, and practises it: you
decide on each change, then send. The partner only talks. Correction is Linguize's job.

## How to use it

- **Open the app.** It opens to the current conversation, or starts a new one if the last
  went quiet more than six hours ago. The partner writes a short opener.
- **Write and tap Send.** Linguize checks the message first. If nothing needs changing it
  goes straight away, marked "Looks good". Otherwise the card opens: tap underlines to see
  why, accept what you want (or Accept all), read Meaning, then tap **Send** in the
  accepted row. **Send as written** is always there, and **Keep editing** goes back to
  the draft.
- **Sent messages** marked "Corrected, 2 changes" open to show what you wrote, with the
  changed parts underlined, and each change in words with its reason.
- **Language.** The language button in the bar (ES, CA) switches the conversation. A new
  conversation starts in the language you last chose or used in the chat; failing that,
  the only language your selection menu names; then the language you check most in
  History; then Spanish.
- **Conversations.** The menu button opens the list: New conversation, and each one with
  its title (from the first exchange), language and last message time. Rename or delete
  from a row's menu; a deletion can be undone from the snackbar.
- **Home and Settings.** The house button opens Home (Try it, History and every Lab entry)
  above the chat, so Back returns to it. The gear opens Settings.
- **Lab settings** (Settings, Chat): **Open to chat** (on) and **Check as I type** (off).
  With Open to chat off, the app starts on Home as before, and Home has a Chat row.
  With Check as I type on, the composer underlines changes while you pause, like the
  Writing pad; tap an underline to accept or dismiss it, and Send sends at once.

## How it works

- **Send flow** (`core/chat/SendFlow.kt`): a pure state machine, Composing, Checking,
  Reviewing. A clean `CheckResult.Reviewed` delivers at once; anything else (changes,
  unclear, wrong language, a failure) opens the review. The review card is the shared
  `ResultCard` on a chat-owned `CheckViewModel` (Origin "tester", so nothing is recorded
  in History). `ReviewActions.applyLabel` lets the card's Apply say Send.
- **Message record** (`core/chat/ChatModel.kt`): each writer message keeps `raw` (as typed),
  `text` (as sent) and `changes`, each accepted change anchored to the raw text. A fix that
  a rewording later replaced is kept too: the writer still got that part wrong.
- **Live checking** (`core/chat/LiveRecord.kt`) reuses the Writing pad's `PadEngine` and
  `LiveTextField`. Each accepted underline leaves a marker over its replacement; typing
  moves markers with the text, and typing into one makes it the writer's own. On send the
  markers are turned back into what was typed, giving the same record.
- **Partner** (`ChatPartner.kt`, `llm/chat/ChatPrompt.kt`, `assets/lab_chat_prompt.md`
  and `lab_chat_schema.json`): the provider clients' `generate` path with its own
  contract. The instructions forbid any comment on grammar, keep replies short and just
  above the writer's level, and end with `App.userMemory.forPrompt()`. The request carries
  the newest turns that fit (40 turns, 12,000 characters) as JSON data. The first reply
  also names the conversation; without a title, the first message's first line is used.
  With no key or no connection, a fixed opener starts the conversation.
- **Memory**: once a conversation has new messages and has been quiet for 20 minutes, or
  the writer leaves it (switches, closes the chat, the app goes to the background), Chat
  calls `App.userMemory.learnFrom("chat:<id>", messages)` and notes how many messages it
  learned from. Deleting a conversation calls `forget("chat:<id>")`; Undo restores it
  unlearned, so it is learned again when quiet.
- **Storage**: SQLite database `lab_chat` (`data/chat/ChatStore.kt`), tables
  `conversations` and `messages`; preferences file `lab_chat` for the two settings and
  the last language. Chat never writes to History.
- **Corpus**: `ChatCorpusSource` adds source `chat` to `App.corpus`: each writer message's
  raw text in its conversation's language, with accepted fixes' spans as `corrected`.
  Partner replies and sent text never enter it; rewordings are not marked as corrected.
- **Start**: `ui/home/ChatStart.kt` makes the chat the navigation root when Open to chat
  is on; `Navigation.open(Home)` then puts Home above it.

## Limits

- The partner's level is judged from the conversation itself; there is no level setting.
- Live checking and the pre-send check run the full check each time, like Try it.
- Titles come from the model or the first line; the writer can rename.
- Memory learns only when a `UserMemory` is supplied; until then the partner meets the
  writer fresh each conversation.
- A message sent while the partner is still replying cancels that reply; the next reply
  answers both.
