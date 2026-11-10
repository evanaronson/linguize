# Chat

Linguize opens to a conversation. A friendly native speaker says hello in the language
you last used, and you just talk. Before each message goes, Linguize checks it on the same
card as a message in any other app, so the partner never has to comment on your grammar.

## The idea

The chat is a stand-in for real conversations when real life doesn't produce enough
writing. There are no separate worlds: everything you write and commit to, to a person or
to the chat partner, is the same kind of evidence. So a chat message is saved in History
exactly like a check put back in another app, and everything that learns from History
(Words, Your conjugations, Your patterns, Practice) learns from the
chat too. History is "everything you wrote, with how it was corrected".

The chat is the same as checking a real message. The only difference is that its entry
point forces the check, because there is no selection menu to choose it from.

## How to use it

- **Open the app.** It opens to the current conversation in the app's current language (any
  language under Linguize; the latest one, unless it went quiet more than six hours ago), with the list of
  conversations one Back away. With none, it opens on the list. A new conversation's
  partner writes a short opener.
- **Write and tap Send.** Linguize checks the message first, always. With nothing to
  review it goes straight away, marked "Looks good". Otherwise the same card as in other
  apps opens: highlights, reasons, accept one change or Accept all, Meaning, clarifying.
  Its last button says **Send** where other apps say **Replace**, and sends the text with
  whatever you accepted, exactly as Replace puts text back in another app. Accepting is up
  to you: with nothing accepted, **Send as written** under the card sends what you typed.
  The check reads your message as a reply to the partner's last message, so it can catch a
  *tú* answer to an *usted* question or a pronoun that doesn't match ([Replying to](reply-context.md));
  the card also says [how it lands](tone-check.md). **Keep editing** goes back to the draft. When the
  check fails (no key, no connection) or can't read the message (unclear, another
  language), the card shows why, with Retry or clarifying; there is no way to send without
  a check.
- **Sent messages** marked "Corrected, 2 changes" (or "Reworded, 2 changes" when every
  accepted change was a rewording, not a fix) open to show what you wrote, with the
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
- **Language.** The app's one language selector, in the top bar of the Chat list (and the
  other three tabs), sets the current language for the whole app
  ([Language scope](language-scope.md)). Under **Linguize** (Auto, the default) the list
  shows every conversation, each with its language's flag; under **Catalanize** or
  **Castilianize** it shows that language's. A conversation keeps its language once
  created, so its own bar has no selector. Opening a conversation in another language (from
  History) makes that the current language, unless the app is on Linguize, which already
  covers it.
- **Conversations.** The Chat tab's top level is the list: each conversation with its
  title, who the partner plays, the last message ("You: …" when it was yours) and its time,
  newest first. Without a key, the list says so first, with Open settings. Tap one to
  open it; its bar has Back to the list. Rename or delete from a row's menu. Delete asks
  first: it deletes the conversation, its saved checks in History and what memory learned
  from it, for good.
- **New conversation**, the button at the bottom of the list, opens a small sheet:
  optionally, who you are talking to (a person memory knows, offered as suggestions, or
  anyone you describe: "my kid's teacher"; memory's people are under "People you write to"). There is no language choice in the sheet: the
  conversation starts in the app's language, or, under Linguize, in the chat's start
  language (the one you last used in the chat; failing that, the only language your
  selection menu names; then the language you check most in History; then Spanish). A
  line in the sheet says which, and that the selector at the top changes it. Left empty,
  the usual friendly partner starts. With someone, the partner plays them, in their
  register, and still never corrects you; the title shows who.
- **The rest of the app.** The bottom bar under the list and a conversation has Notes (the
  editor), Progress and History; Back from any of them returns to the chat. The gear on the
  list (as on every tab) opens Settings; a conversation's bar is Back and its title only.
  **Open to chat** (Settings, Chat, on by default) can switch to starting on Notes. See
  [Home and navigation](home-ia.md).
- **History.** Each sent message is one row in History, the same as a message checked
  in another app, labelled "Chat · Weekend plans" where an app row says "WhatsApp". It
  opens the saved check, which also offers **Open conversation**. The Chat chip filters
  History to chat messages. See [One list of messages](history-flat.md).

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
  migrations were added to History. A message whose check failed, was unclear or in
  another language is never sent, so it is never saved. With History off, nothing is
  saved; the chat still works from its own store.
- **Links.** The chat's database (`lab_chat`, version 3) notes each message's History
  session (`messages.session_id`) and each reply's translation (`messages.translation`).
  `ChatStore.threads` maps sessions to conversations; History uses it for a chat row's
  label and the saved check's Open conversation, and deleting a conversation uses
  `sessionIds` to delete its sessions. History doesn't group rows by conversation.
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
- **Start**: `ui/home/ChatStart.kt` makes the chat's list the navigation root when Open to
  chat is on, with `ChatThread(null)` (the current conversation) above it; the other tabs
  open above the list. `ChatListScreen` and `ChatThreadScreen` (`ui/chat/ChatScreen.kt`)
  share one `ChatViewModel`: `showList`, `openCurrent(language)` (`ChatStart.opening` on the
  latest conversation in that language), `open(id)` and `newConversation`, which returns
  the new id so the list can open it. There is no drawer, and no way to change a
  conversation's language (`setLanguage` was removed).

See [Chat tab: information hierarchy](ia-chat.md) for why the tab is laid out as it is.

## Limits

- The partner's level is judged from the conversation itself; there is no level setting.
- Register gaps are a marker-word heuristic, not a classifier.
- Two identical messages within ten minutes may reuse one answer; the corpus then counts
  that text once, like any repeated check.
- Deleting a chat message's row in History deletes only that History session: the
  conversation and the message in it stay, and the message keeps a note of the deleted
  session that nothing reads any more.
- Check as I type, the inline review, Send as written and Skip were removed: the chat
  checks exactly like any other message.
