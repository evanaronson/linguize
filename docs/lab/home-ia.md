# Home and navigation

About 20 experiments each added their own entry: 15 rows on Home, rows in Settings, rows
above History, and footer rows on the check card. Home had turned into a list of features,
with learning views ("stats") mixed in with tools. This page reorganizes the app around
what a person comes to do. It doesn't add features.

## Principles

1. **Organize by the person's job, not by the feature list.** Someone opens the app to
   talk, to check or write one specific thing, to see how they're doing, or to look back.
   Each job gets one place.
2. **A few top-level places, always visible.** Four destinations in a bottom bar. Settings
   stays the gear in each top bar, because it's set up once, not visited.
3. **One clear home per thing.** Where two entries led to the same page, keep the one that
   matches the job. Context entries stay where the person already is: the card rows act on
   the text in front of them, so they stay.
4. **Stats are one place.** Patterns, Practice, Words and Your conjugations all read the
   same History. They're one area, Progress, with Practice as
   the thing to do and the rest as views.
7. **The language is context, not a filter.** The writer's current language is set once,
   in the top bar, and Chat, Write, Progress and History all follow it (see
   [Language scope](language-scope.md)). No page has a language switch of its own.
8. **Filters are not buttons.** Anything that chooses what a page shows is a quiet
   dropdown ("Sort: Newest ▾"); buttons are only for actions (see
   [Copy style](../copy-style.md)).
5. **Nothing becomes unreachable.** Every experiment still has a path. Rarely used tools
   are a tap lower, not gone.
6. **Back is predictable.** The start destination is the root. Every other tab sits right
   above it, so Back from any tab returns to the start, and Back from the start leaves.

## The person's jobs

| Job | How often | What serves it |
|---|---|---|
| Talk and practise | Daily, the main reason to open the app | Chat (conversations, Practise with…) |
| Write or check one specific thing | Several times a week | Write (one editor with drafts; every check fills [gaps](gaps-in-check.md)); card rows: How it lands, Say it in Catalan, Replying to, Second opinion |
| Understand something someone sent | Weekly | Understand copied text; share sheet |
| See how I'm doing | Weekly | Practice, Your patterns, Words, Your conjugations, Phrasebook |
| Look back at what I wrote | Weekly | History (one row per message, any source) |
| Set up | Once, then rarely | Keys and model, languages, selection menu, accessibility button, popup, per-app voice, What Linguize knows, Open to chat, History switch |
| In another app, before sending | Many times a day, outside this app | Selection menu, accessibility button, Quick tile, launcher shortcuts, share |

## Structure

**Top bar.** The page title, the app's **language switch** (the current language's flag and
"▾") and the Settings gear, on the four tabs and in a conversation.

**Bottom bar: Chat, Write, Progress, History.** Shown on these four pages and in a
conversation (the app opens there), hidden on the other pages they open and while the
keyboard is up. The app starts on Chat, or on Write when **Open to chat** is off
(Settings, Chat).

- **Chat**: the list of conversations in the current language (title, who the partner
  plays, last message, time), with **New conversation** at the bottom. A conversation opens
  above the list with Back to it, its title and the same language switch. The app opens
  straight into the current conversation, the list one Back away.
- **Write**: the add-key strip until there is a key; **Write and check**: one editor
  that keeps drafts, checked with the same card as everywhere, with Copy, Share and New
  draft (see [Write](write.md)); **Drafts** when there are others.
- **Progress**, all in the current language: **Practice** (always shown here: "4 due" or
  "Nothing due"); **Your writing**: Words ("312 words you've written in Catalan": the words
  you've used and the common ones you haven't, in one list), Your conjugations ("12 of 40
  forms used"). **Fixes and phrases**: Your patterns (top groups and a sparkline once
  there's enough), Phrasebook (count). **Time**: Time spent (this week's total and a
  sparkline; see [Time spent](time-spent.md)).
- **History**: the list, with a language dropdown (the current language, or All languages)
  and a source dropdown above it (see [One list of messages](history-flat.md)).
- **Settings** (gear): AI service and Second opinion; Checking (What to suggest); Writing
  preferences and Per-app voice; About you (What Linguize knows); **In other apps**:
  Selection menu, Accessibility button, Popup position; Chat (Open to chat); History.

## Before and after

| Entry | Before | After | Why |
|---|---|---|---|
| Chat | App start; Home row; History threads | App start (default); bottom bar; Open conversation on a chat message's saved check | A tab, so the Home row and the chat bar's house button are redundant |
| Conversation list | A drawer behind the chat bar's menu icon | The Chat tab's top level; a conversation opens above it with Back to the list; the drawer and its icon are gone | The list sat in the top nav and didn't read as part of the chat; a messaging app's list is its home |
| New conversation | The drawer's first row | The list's main button, at the bottom; the sheet starts in the current language | The primary action of the list |
| Language | Progress's Catalan/Spanish switch (Words and conjugations only); the chat bar's ES/CA button; Words' language chips; Your conjugations' switch | One switch in the top bar of every tab and conversation; Progress, Chat, Write and History follow it | The top of Progress followed the switch and the bottom didn't; the language is context for the whole app |
| A conversation's language | Changeable from its bar | Fixed once created; switching the app's language goes back to the list | A conversation is in one language |
| Filters (History source, Words' Show, Sort and Count by, deck choice, the new-conversation language) | Chips, outlined or filled buttons, radio rows | One quiet dropdown ("Sort: Newest ▾") | Filters must not look like actions |
| Try it | Home, top | Write, top, as the Write editor (see [Write](write.md)) | The core "check this" job; merged with the Writing pad |
| Add an API key strip | Home | Write (the chat has its own) | Where the first check happens |
| Writing pad | Home, own section | Removed; its drafts open in the Write editor | Same job as Try it, with a second check experience ([Write](write.md)) |
| Fill the gaps | Home row; card row when text has gaps | Removed; every check fills [gaps](gaps-in-check.md); its saved page opens Write | Part of the core check, everywhere |
| Help me reply | Home, own section; Understand card | Removed; saved pages open Write | Interpretation, not part of the writing loop; another app does it |
| Understand a photo | Home, own section; share sheet | Removed, with the image share target; saved pages open Write | Same: send the photo to a general assistant |
| Practice | Home, only while due | Progress, always (count or Nothing due) | Pull, not push: visiting Progress is asking |
| Your patterns | Home card once worth showing; row above History | Progress | One place for stats |
| Your words | Home card; row above History | Progress › Words, with the language switch | Merged with Vocabulary gaps: the words you know are a subset of all the words |
| Your conjugations | Home, own section | Progress, with the language switch | |
| Vocabulary gaps | Home row | Progress › Words (Not yet filter, coverage in the header); its saved pages open Words | One list, not two |
| Phrasebook | Home, own section; star on the card | Progress › Fixes and phrases; star unchanged | Things kept from your own writing |
| Recent (last five) and View all | Home | Removed; History is a tab | A preview of a page that's one tap away |
| History | View all on Home; Settings › History | Bottom bar; Settings › History unchanged | |
| Accessibility button setup | Home, bottom | Settings › In other apps | Set up once |
| Selection menu, Popup position | Settings › Checking, Settings › Popup | Settings › In other apps | Grouped with the accessibility button: how Linguize shows up elsewhere |
| Settings | Gear on Home; gear in the chat bar | Gear on all four tabs | |
| Open to chat | Settings › Chat: Chat or Home | Settings › Chat: Chat or Write | Same preference, same stored value |
| Try it launcher shortcut (now "Write") | Opened the start page (the chat, by default) | Opens Write above the start | It promised the editor |
| Practice's last button | Back to Home | Back to Progress | Where Back goes |
| What Linguize knows, Per-app voice, Second opinion | Settings | Settings | Set up, edited rarely |
| Card rows (Meaning, How it lands, Say it in…, Second opinion, Replying to, voice chip, Teach me, star) | Card | Card | They act on the text in front of you |
| Selection menu, accessibility button, Quick tile, Check and Understand shortcuts, share text, popup's app button | System | System | Unchanged; the app button and "Open settings" still open the app and Settings |

## Navigation model

`ui/home/Navigation.kt`. `Screen.tabs` lists the four destinations. A path starts at the
start destination; opening a tab replaces everything above the start with that tab
(`[Chat]`, `[Chat, Progress]`), and any other screen opens above the current one
(`[Chat, Progress, Patterns, Pattern, Saved check]`). `Navigation.tab` is the bottom bar's
selection. Saved state is the same list of tokens as before.

`Screen.Chat` is the list of conversations; `Screen.ChatThread(id)` is one conversation
above it (`lab_chat_thread:<id>`). The app starts at `[Chat, ChatThread(null)]`: null is
"the current conversation", resolved when shown (`Navigation.replace` swaps in its id, or
Back returns to the list when there is none, so nothing starts on its own). Another
conversation replaces the open one; the Chat tab from a conversation returns to the list;
History's Open conversation is `open(Chat).open(ChatThread(id))`. A language switch with a
conversation open is `afterLanguageSwitch()`: Back to the list. The Write shortcut and a
popup's Open settings don't open a conversation. A saved `[lab_chat]` restores as the
list. Write keeps the token `home`,
so a saved path from before restores. An old `[lab_chat, home, history]` restores as
`[lab_chat, history]`. The tokens of removed pages above Write (`lab_gap_filler`, `lab_reply`, `photo`)
restore as Write. `HomeActivity.writeIntent` asks for Write. The Write shortcut uses
it.

## What didn't change

- Feature internals, view models, prompts, History data, the check card and its rows.
- The selection-menu, accessibility and popup flows, and the share, tile and clipboard hosts.
- Each feature page and its title. (Their own language switches are gone: see
  [Language scope](language-scope.md).)
- What Linguize knows stays in Settings. It's something you edit, not a view of progress.
- There's no in-app page for the Quick tile. Android adds tiles from Quick Settings, and
  the tile's page explains that.
- `HistoryViewModel.recent` (and its tests) stays, though nothing shows Recent now.

## Limits

- Switching tabs doesn't keep each tab's inner path. Coming back to Progress opens its top,
  not the pattern you were reading.
- Progress counts conjugations when it opens (on the phone, no model). That's the one
  view that reads the corpus there.
