# Chat

Linguize opens to a conversation. A friendly native speaker says hello in the language
you last used, and you just talk. Before each message goes, Linguize checks it, so the
partner never has to comment on your grammar.

## The idea

The chat is a stand-in for real conversations when real life doesn't produce enough
writing. There are no separate worlds: everything you write and commit to, to a person or
to the chat partner, is the same kind of evidence. So a chat message is saved in History
exactly like a check put back in another app, and everything that learns from History
(Words, Your conjugations, Your patterns, Practice) learns from the
chat too. History is "everything you wrote, with how it was corrected".

## How to use it

- **Open the app.** It opens to the current conversation, or starts a new one if the last
  went quiet more than six hours ago. The partner writes a short opener.
- **Write and tap Send.** Linguize checks the message first. With nothing to fix it goes
  straight away, marked "Looks good". With fixes, they show over the composer, marked in
  your own words: tap one (in the text or the list) for its reason, Skip the ones you don't
  want, then **Send with fixes**, or **Send as written**. **Details** opens the full card
  (Meaning, assumptions, rewordings); **Keep editing** goes back to the draft.
- **Sent messages** marked "Corrected, 2 changes" open to show what you wrote, with the
  changed parts underlined and each change in words with its reason.
- **Translate.** Each partner message has a quiet Translate under it (and in the bubble's
  long-press menu). The translation into your own language shows under the bubble, with a
  note or two on tricky parts; tap again to hide it. Translations are kept for the session
  and never saved or counted as your writing.
- **Select and copy.** Long-press any message text to select part of it, with the
  system's Copy and other apps' text actions. A long-press on the bubble's edge opens Copy
  message (and Translate). Screen readers get Copy and Translate as actions.
- **Language.** The language button in the bar (ES, CA) switches the conversation. A new
  conversation starts in the language you last chose or used in the chat; failing that,
  the only language your selection menu names; then the language you check most in
  History; then Spanish.
- **Conversations.** The menu button opens the list: New conversation, Practise with…,
  and each conversation with its title, language and last message time. Rename or delete
  from a row's menu. Delete asks first: it deletes the conversation, its saved checks in
  History and what memory learned from it, for good (History has no Undo for deletion
  across features, so neither does this).
- **Practise with…** starts a conversation where the partner stands in for someone you
  really write to: pick a person memory knows ("Jordi, my landlord, formal"), or describe
  one ("my kid's teacher, about a sick day"). The partner plays them, in their register,
  and still never corrects you. The conversation's title shows who.
- **The rest of the app.** The bottom bar under the chat has Write (Try it and the other
  tools), Progress and History; Back from any of them returns to the chat. The gear opens
  Settings. **Open to chat** (Settings, Chat, on by default) can switch to starting on
  Write. See [Home and navigation](home-ia.md).
- **History.** Each conversation is one row in History ("Chat,
  Weekend plans" over "5 messages, 2 corrected"); tap it to open the conversation. Checks
  sent to other apps keep their own rows, so the chat never buries them.

## How it works

- **Send flow** (`core/chat/SendFlow.kt`): a pure state machine, Composing, Checking,
  Reviewing. An answer with no fixes delivers at once (rewordings alone don't hold a
  message up; withheld fixes do). The engine is the shared `CheckViewModel`, run with
  `Origin.Chat`; the inline fixes and the Details card change the same revision.
- **Saving** (History): on send, the card closes through `CheckHistory` with the sent text
  as final text (`CheckViewModel.dismissSent`): accepted fixes are Accepted, skipped ones
  Ignored, a clean message has no suggestions. `Origin.Chat` is recorded and, as the only
  exception to the "final text differs" rule, kept when sent unchanged
  (`Origin.keepsUnchanged`, `HistorySchema.VISIBLE_HISTORY`). No columns, tables or
  migrations were added to History. A message whose check failed, was unclear or in
  another language and is sent as written is not saved. With History off, nothing is
  saved; the chat still works from its own store.
- **Threads.** The chat's database (`lab_chat`, version 2) notes each message's History
  session (`messages.session_id`). `ChatStore.threads` maps sessions to conversations;
  History and Recent fold a conversation's rows into one where its newest message is
  (`ui/history/ChatThreads.kt`). Recent reads extra rows as needed so it still shows five.
- **Corpus.** There is no separate chat source any more: the History-based `check` source
  covers chat messages (raw text, accepted fixes as `corrected`, rewordings not), with
  `Utterance.origin = "chat"`. The "re-check of put-back text" rule doesn't apply to chat.
  Partner replies and translations never enter. Patterns and Practice pick up chat fixes
  and label each example "Chat" or "Another app".
- **Partner** (`ChatPartner.kt`, `llm/chat/ChatPrompt.kt`, `assets/lab_chat_prompt.md`
  and `lab_chat_schema.json`): the provider clients' `generate` path with its own
  contract. The instructions forbid any comment on grammar, keep replies short and just
  above the writer's level, and end with `App.userMemory.forPrompt()` and the practice
  targets. The request carries the newest turns that fit (40 turns, 12,000 characters),
  and `persona` for a stand-in.
- **Practice targets** (`core/chat/PracticeTargets.kt`, `ChatTargets.kt`): from the corpus
  in the conversation's language, up to three rare or unused tense-person cells from the
  conjugation map (simple tenses after the present), up to five top unused words from the
  bundled vocabulary deck, and registers the writer seldom writes (formal or casual,
  judged by marker words). With at least 30 real (non-chat) messages, gaps are read from
  real writing only, so chat practice doesn't hide what real writing lacks. Built on a
  background scope, kept per language with the corpus fingerprint, re-read at most every
  five minutes, at most 900 characters, and never waited for more than four seconds.
  The prompt asks for natural openings (plans for the future, "what would you do if…",
  topics that need the words), one at a time, never announced, never quizzed.
- **Translate** reuses `Understander` (the Understand prompt), kept in memory per message
  (`ui/chat/Translations.kt`).
- **Memory**: once a conversation has new messages and has been quiet for 20 minutes, or
  the writer leaves it, Chat calls `App.userMemory.learnFrom("chat:<id>", messages)`.
  Deleting a conversation calls `forget("chat:<id>")`.
- **Start**: `ui/home/ChatStart.kt` makes the chat the navigation root when Open to chat
  is on; the other tabs open above it.

## Limits

- The partner's level is judged from the conversation itself; there is no level setting.
- Register gaps are a marker-word heuristic, not a classifier.
- Two identical messages within ten minutes may reuse one answer; the corpus then counts
  that text once, like any repeated check.
- A History row for a chat message deleted from History itself stays in the conversation
  (the message is still there); its check is gone.
- Check as I type was removed: one check-before-send flow keeps it simple.
