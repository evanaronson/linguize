# Chat

Linguize opens to a conversation. A friendly native speaker says hello in the language
you last used, and you just talk. Before each message goes, Linguize checks it on the same
card as a message in any other app, so the partner never has to comment on your grammar.

## The idea

The chat is a stand-in for real conversations when real life doesn't produce enough
writing. There are no separate worlds: everything you write and commit to, to a person or
to the chat partner, is the same kind of evidence. So a chat message is saved in History
exactly like a check put back in another app, and everything that learns from History
(Your words, Vocabulary gaps, Your conjugations, Your patterns, Practice) learns from the
chat too. History is "everything you wrote, with how it was corrected".

The chat is the same as checking a real message. The only difference is that its entry
point forces the check, because there is no selection menu to choose it from.

## How to use it

- **Open the app.** It opens to the current conversation, or starts a new one if the last
  went quiet more than six hours ago. The partner writes a short opener.
- **Write and tap Send.** Linguize checks the message first, always. With nothing to
  review it goes straight away, marked "Looks good". Otherwise the same card as in other
  apps opens: highlights, reasons, accept one change or Accept all, Meaning, clarifying.
  Its Apply button says **Send**, and sends the text with whatever you accepted, exactly as
  Apply puts text back in another app. Accepting is up to you: with nothing accepted, Send
  under the card sends what you typed. **Keep editing** goes back to the draft. When the
  check fails (no key, no connection) or can't read the message (unclear, another
  language), the card shows why, with Retry or clarifying; there is no way to send without
  a check.
- **Sent messages** marked "Corrected, 2 changes" open to show what you wrote, with the
  changed parts underlined and each change in words with its reason.
- **Translations.** Every reply comes with its translation into your own language, in the
  same answer. Tap a reply to flip it to the translation (tinted, with a short crossfade);
  tap again to flip back. "Tap to translate" shows under the latest reply until you first
  do. Replies saved before this have no translation of their own: tapping one asks
  Understand once and keeps it. Translations never reach History or the corpus.
- **Select and copy.** Long-press any message text to select part of it, with the
  system's Copy and other apps' text actions. A long-press on the bubble's edge opens Copy
  message (and Copy translation while it shows). Screen readers get Show translation or
  Show original, Copy message and Copy translation as actions.
- **Language.** The language button in the bar (ES, CA) switches the conversation. A new
  conversation starts in the language you last chose or used in the chat; failing that,
  the only language your selection menu names; then the language you check most in
  History; then Spanish.
- **Conversations.** The menu button opens the list: New conversation, and each
  conversation with its title, language and last message time. Rename or delete from a
  row's menu. Delete asks first: it deletes the conversation, its saved checks in History
  and what memory learned from it, for good.
- **New conversation** opens a small sheet: optionally, who you are talking to (a person
  memory knows, offered as suggestions, or anyone you describe: "my kid's teacher"), and
  the language. Left empty, the usual friendly partner starts. With someone, the partner
  plays them, in their register, and still never corrects you; the title shows who.
- **The rest of the app.** The bottom bar under the chat has Write (Try it and the other
  tools), Progress and History; Back from any of them returns to the chat. The gear opens
  Settings. **Open to chat** (Settings, Chat, on by default) can switch to starting on
  Write. See [Home and navigation](home-ia.md).
- **History.** Each conversation is one row in History ("Chat,
  Weekend plans" over "5 messages, 2 corrected"); tap it to open the conversation. Checks
  sent to other apps keep their own rows, so the chat never buries them.

## How it works

- **Send flow** (`core/chat/SendFlow.kt`): a pure state machine, Composing, Checking,
  Reviewing. A reviewed answer with no changes at all delivers at once; anything else opens
  the card (`ResultCard` and `CardActions` on a `CheckViewModel` run with `Origin.Chat`,
  `ReviewActions.applyLabel` = Send). The card's Send delivers the revision as accepted.
  Failures and unclear or wrong-language answers stay on the card: only Retry, clarifying
  or Keep editing leave it.
- **Saving** (History): on send, the card closes through `CheckHistory` with the sent text
  as final text (`CheckViewModel.dismissSent`): accepted changes are Accepted, the rest
  Ignored, a clean message has no suggestions. `Origin.Chat` is recorded and, as the only
  exception to the "final text differs" rule, kept when sent unchanged
  (`Origin.keepsUnchanged`, `HistorySchema.VISIBLE_HISTORY`). No columns, tables or
  migrations were added to History. With History off, nothing is saved; the chat still
  works from its own store.
- **Threads.** The chat's database (`lab_chat`, version 3) notes each message's History
  session (`messages.session_id`) and each reply's translation (`messages.translation`). `ChatStore.threads` maps sessions to conversations;
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
  and `persona` when the conversation has one. Each answer has `reply`, `translation`
  (into the writer's own language) and `title`; a missing translation never costs the reply.
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
- **Flipping** (`ui/chat/Flips.kt`) shows the stored translation; only replies saved
  before version 3 fall back to `Understander`, once, kept in memory.
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
- Check as I type, the inline review, Send as written and Skip were removed: the chat
  checks exactly like any other message.
