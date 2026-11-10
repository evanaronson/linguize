# Chat tab: information hierarchy

What's on the Chat tab, below the shared top bar and above the bottom bar, and why it's in
that order. The tab was already in good shape. Most of it stays as it is, and the changes
below are small. See [Chat](chat.md) for how the chat works and
[Home and navigation](home-ia.md) for the bars.

## The person's jobs, most important first

1. **Carry on talking.** Open the app, land in the current conversation, read the reply and
   answer. This happens every day and is the reason the tab exists.
2. **Send a message they trust.** Write, tap Send, review the check on the card, then send.
   This happens with every message.
3. **Understand the partner.** Flip a reply to its translation. Several times a conversation.
4. **Look back at what changed.** Open "Corrected, 2 changes" under a sent message. Sometimes.
5. **Pick a conversation up again, or start a new one.** Use the list, New conversation and,
   optionally, who the partner plays. A few times a week.
6. **Tidy up.** Rename or delete a conversation. Rarely.
7. **Get set up.** Add a key the first time. Once.

## Inventory (before)

**List (the tab's root)**

| Element | Position | Prominence |
|---|---|---|
| Conversation rows: title, flag (under Linguize), time; "With …" when the partner plays someone; last message | Whole page, newest first | Primary |
| Row menu (⋮): Rename, Delete (asks first) | End of each row | Tertiary |
| New conversation | Extended button, bottom end, floating | Primary action |
| Empty state: "No conversations yet" / "No conversations in Catalan yet", "Start one and your partner writes first." | Top | Only with no rows |
| No-key strip | Not on the list. It appeared only after a conversation was opened | — |

The last message didn't say who wrote it. A screen reader heard only "title, language,
time": the persona and the last message were dropped.

**New-conversation sheet**

Title; "Who are you talking to?" field; suggestions from memory (unlabelled buttons); then
"Optional. Leave it empty…" under the suggestions, away from the field it qualified; the
language line; Cancel, Start.

**A conversation (thread)**

| Element | Position | Prominence |
|---|---|---|
| Page bar: Back ("Conversations"), title, "With …" | Top (shared) | — |
| No-key strip | Top of the body, without a key | Secondary |
| Messages: the writer's on the right in ink, the partner's on the left on paper | Middle, anchored to the bottom | Primary |
| Under a sent message: "Looks good" / "Sent as written" / "Corrected, N changes ▾" | Under the bubble, small and muted | Tertiary |
| Changes: "You wrote" with the changes underlined, then each change and its reason | Under the bubble when opened | Secondary when open |
| "Tap to translate" | Under the latest reply until the first flip | Tertiary |
| Writing a reply (•••) / No reply yet + Retry | Bottom of the messages | Secondary |
| Composer and Send | Bottom | Primary |
| While checking: "Checking your message", Keep editing | Replaces the composer | Primary |
| Check card (up to 62% of the height, scrolls), then Keep editing and, with nothing accepted, Send as written | Replaces the composer | Primary |

## Target hierarchy

**List.** The rows are the page. Each row answers "which conversation" (title, and who the
partner plays), "is it my turn" (the last message, with "You:" when it was yours) and "when"
(the time). New conversation stays the one primary action, bottom end. The menu stays
tertiary. Without a key, the first thing on the list says so, because nothing on the tab
works without one.

**Sheet.** Ask one thing, who you're talking to. The field's own hint says it's optional.
Then the people memory knows, under a label saying what they are. Then which language the
conversation will be in, just before Start, because it can't be changed afterwards.

**Thread.** The conversation is primary. Your own message's check status is tertiary and
says what happened accurately. The composer, or the card that replaces it, is the one place
for action. The card sits where the composer was, the newest messages stay in view above it,
and its exits (Keep editing, Send as written) sit right under it, always visible however
long the card is.

## Current vs target

| | Current | Target | Change |
|---|---|---|---|
| Row's last message | The text, whoever wrote it | "You: …" when the writer sent it | Yes. It says whose turn it is, which is what you scan for (and a reply that never came shows) |
| Row for screen readers | "Title, language, time" | Title, who, language, time, last message | Yes. The row showed more than it said |
| No key | Only inside a conversation | Also at the top of the list | Yes. On first run the app opens on the empty list |
| Sheet's "Optional…" | Below the suggestions | The field's supporting text | Yes. It qualifies the field |
| Sheet's suggestions | Unlabelled buttons | Under "People you write to" (memory's own category name) | Yes. Memory notes as bare buttons read as unexplained text |
| Sent message with only rewordings | "Corrected, N changes" | "Reworded, N changes"; "Corrected" when a fix was accepted | Yes. A rewording isn't a mistake. Saying "Corrected" makes the writer doubt correct writing |
| "Looks good" under each clean message | Every message | Unchanged | No. It's quiet and it's evidence for that message |
| Card placement, Keep editing, Send as written | In place of the composer, exits below the card | Unchanged | No. Already right; see below |
| New conversation | Floating, bottom end | Unchanged | No |
| Rename, Delete | Row menu | Unchanged | No |

## Considered and rejected

- **Swipe to delete on rows** (as in History). Deleting a conversation also deletes its
  checks and what memory learned from it, so it should ask first. The menu is the right
  weight for that.
- **A second New conversation button in the empty state.** The floating button is already
  on screen, so a second one would duplicate the primary action.
- **Day headers on the list.** Rows already show the time today and the date otherwise, and
  there are rarely enough conversations to need grouping.
- **Showing only the latest "Looks good"**, the way messaging apps show "Delivered". Each
  status belongs to its own message's check, and hiding it would lose that per message.
- **Showing the draft as a pending bubble while it's checked.** The card already shows the
  text. A pending bubble would mean a new message state, which is a behaviour change.
- **Moving the sheet's language line to the top.** It's the last thing to confirm before
  Start, and it sits right above Start.
- **Card exits inside the card.** The card's internals belong to the check card. The chat
  owns only its Keep editing and Send as written, which stay outside the scrolling card so
  they're never scrolled away.
- No new expand pattern. "Corrected, N changes ▾" keeps the existing one; the check card's
  expand decision is made elsewhere.

## How it works

- `core/chat/ChatLabels`: `sent(changes)` gives Corrected or Reworded.
  `rowDescription(…)` gives the row's spoken description.
- `ConversationSummary.lastFromWriter`: `ChatStore.conversations()` also reads who wrote
  the latest message. This is a read-only subquery, with no schema change.
- `ConversationList(needsKey, onOpenSettings)`, from `ChatListScreen(language,
  onOpenSettings)`: one argument added in `HomeActivity`.
- Strings: `strings_lab_ia_chat.xml`.
- Untouched: send flow, timing (`DraftTimer`, card review time), History, memory, prompts.

## Tests

`core/chat/ChatLabelsTest` and `ui/chat/ConversationListTest`. The second checks the
store's last speaker, the "You:" line, what a screen reader hears for a row, and the no-key
strip on the list.
