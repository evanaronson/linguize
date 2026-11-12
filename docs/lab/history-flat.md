# One list of messages

History is a history of messages: what you wrote, and how it was corrected. Before this
change it showed two kinds of row for the same thing. A message checked in another app was
its own row and opened its saved check; chat messages were folded into one row per
conversation ("Chat, Weekend plans · 5 messages, 2 corrected") that opened the chat.
The grouping existed only because the app knows chat threads; it can't know WhatsApp
threads, so the two could never look alike. Now every recorded message is one row of the
same shape, and the list is grouped by day.

## The user moment

"What did I write this week, and what did I get wrong?" The answer is the same whether you
wrote it to your landlord in WhatsApp or to the chat partner. A dense list is fine as long
as it is scannable, so days get headers and sources get a filter.

## How to use it

- Open the **History** tab. Rows sit under **Today**, **Yesterday**, then dates
  ("5 Nov", or "5 Nov 2025" in another year).
- Each row shows the time, where the message was written (the app's name, or
  "Chat · Weekend plans"), its language, the text, and what came of it.
- Tap any row to open its saved check. A chat message's page also has
  **Open conversation**, which opens that conversation in Chat.
- Swipe a row toward the start (or use the screen reader's Delete action) to delete it,
  with Undo, for every source. For a chat message this deletes only its History record:
  the conversation and the message in it stay in Chat.
- The list follows the app's language selector in the top bar: every language under
  **Linguize** (the default), only that language's messages under **Catalanize** or
  **Castilianize** ([Language scope](language-scope.md)). History has no language dropdown
  of its own. When messages come from more than one place, a source dropdown filters it:
  **All sources** (default), each app that appears, **Chat**. It combines with the
  language. The choice survives rotation and coming back to the
  tab. A filter with nothing left says so ("No messages from WhatsApp yet.", "No chat
  messages yet.", "No messages in Catalan yet.").
- The order of the tab and of the saved check is set out in
  [History tab: information hierarchy](ia-history.md).

## How it works

- `ui/history/HistoryList.kt` holds the pure parts: a row's source and label, the
  `HistoryFilter` (All, `App(package)`, Chat, saved as a token), the chips to offer, and
  `historyItems`, which puts a `Day` header before each day's first row.
- `HistoryViewModel` no longer groups. It attaches each chat message's conversation
  (`ChatStore.threads`) to its row as `RecentRow.chat`, used only for the label. A failed
  lookup leaves the row labelled "Chat".
- `HistoryPager` is unchanged: it pages sessions. The screen builds headers from the
  loaded, filtered rows, so a day split across two pages keeps one header. The load-more
  trigger counts the shown items (headers included), so a filter that hides most rows
  keeps loading pages until the list fills or ends.
- `SessionViewModel` looks up the conversation only for `Origin.Chat` sessions and puts it
  on `SessionPage.Shown.chat`. `SessionScreen` labels the page with it and adds the
  Open conversation row when the host passes `onOpenConversation` (HomeActivity sets the
  chat's requested conversation and opens the Chat tab).
- Removed: `ui/history/ChatThreads.kt` (thread rows, grouping, "N messages, M corrected"),
  the thread key, Recent's extra-row window, and `HistoryScreen`'s `onOpenThread`.
  The session-to-conversation mapping stays in the chat store, for labels, links and
  deleting a conversation's sessions.

## Limits

- The chips come from the rows loaded so far. An app whose messages are all older than
  the loaded pages appears once scrolling reaches them.
- Checks from an app Android can't name (uninstalled, or saved without a package) have no
  label and no chip; they show under All.
- No app icons: the label is the app's name only.
- The row text is the saved text's first line with its counts ("1 fix, 1 accepted"), as
  before; there is no inline highlighting of the corrections on the row itself. The saved
  check shows Before, After and each change.
- The strings for the old thread row (`lab_chat_history_*`) are left in the chat's
  strings file, unused, to keep this change off the chat's files.
