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
   stays the gear in the top bar of each tab, because it's set up once, not visited.
3. **One clear home per thing.** Where two entries led to the same page, keep the one that
   matches the job. Context entries stay where the person already is: the card rows act on
   the text in front of them, so they stay.
4. **Stats are one place.** Patterns, Practice, Words and Your conjugations all read the
   same History. They're one area, Progress, with Practice as
   the thing to do and the rest as views.
7. **The language is context, not a filter.** The writer's current language is set once,
   in the top bar, and Chat, Notes, Progress and History all follow it (see
   [Language scope](language-scope.md)). There is one language selector in the app: no
   page, sheet or in-app card has a language choice of its own. Only the popups over other
   apps keep the card's picker, because the app's choice doesn't reach them.
9. **One top bar, two shapes.** Every root tab has the same bar: the wordmark on the left;
   on the right, the language selector then the Settings gear, at the same place and size
   on all four. Every page opened from a tab has Back and that page's title (a
   conversation: its title, and who the partner plays). A tab never repeats its own name:
   the bottom bar already says it. Nothing else draws a top bar.
8. **Filters are not buttons.** Anything that chooses what a page shows is a quiet
   dropdown ("Sort: Newest ▾"); buttons are only for actions (see
   [Copy style](../copy-style.md)).
5. **Nothing becomes unreachable.** Every experiment still has a path. Rarely used tools
   are a tap lower, not gone.
6. **Back is predictable.** The start destination is the root. Every other tab sits right
   above it, so Back from any tab returns to the start, and Back from the start leaves.
   While the keyboard is up, the first Back only closes it (and leaves the field); the next
   Back navigates. This holds everywhere: the app, the new-conversation sheet, the popups
   over other apps and the accessibility window. Every Back handler is a
   `NavigationBackHandler`, which stands aside while the keyboard is up, and each window
   has one `KeyboardBackHandler` that takes that Back (`ui/components/BackNavigation.kt`,
   `backAction`; the accessibility window checks the keyboard itself in `OverlayWindow`).

## The person's jobs

| Job | How often | What serves it |
|---|---|---|
| Talk and practise | Daily, the main reason to open the app | Chat (conversations, Practise with…) |
| Write or check one specific thing | Several times a week | Notes (one editor that keeps notes; every check fills [gaps](gaps-in-check.md) and says how it lands); card rows: How it lands, Second opinion |
| Understand something someone sent | Weekly | Understand copied text; share sheet |
| See how I'm doing | Weekly | Practice, Your patterns, Words, Your conjugations, Phrasebook |
| Look back at what I wrote | Weekly | History (one row per message, any source) |
| Set up | Once, then rarely | Keys and model, languages, selection menu, accessibility button, popup, per-app voice, What Linguize knows, Open to chat, History switch |
| In another app, before sending | Many times a day, outside this app | Selection menu, accessibility button, Quick tile, launcher shortcuts, share |

## Structure

**Top bar.** One component (`AppNavigation`) in two shapes (`TopBar`):

| Where | Left | Right |
|---|---|---|
| Chat, Notes, Progress, History (the root tabs) | The wordmark | The language selector (icon and "▾"), then the Settings gear |
| Any page opened from a tab: a conversation, Words, a saved check, Settings and its pages, … | Back, then the page's title (a conversation: its title, who the partner plays under it) | Nothing |

Pages opened from a tab don't show the selector. A conversation's language is fixed once
created, so switching there would mean nothing. A page Progress opens (Words, Your
conjugations, …) shows the language it was opened in. Switching it in place would change
the page under the reader, so the language is changed on the tab, one Back away.

**Bottom bar: Chat, Notes, Progress, History.** Shown on these four pages and in a
conversation (the app opens there), hidden on the other pages they open and while the
keyboard is up. The app starts on Chat, or on Notes when **Open to chat** is off
(Settings, Chat).

- **Chat**: the list of conversations in the current language (every language, each with
  its flag, under Linguize; title, who the partner plays, last message, time), with **New
  conversation** at the bottom. A conversation opens above the list with Back to it and its
  title. The app opens
  straight into the current conversation, the list one Back away.
- **Notes**: the add-key strip until there is a key; **Write and check**: one editor
  that keeps notes, checked with the same card as everywhere in the app's language, with
  Copy, Share and New note (see [Notes](notes.md)); **Other notes** when there are others.
- **Progress**, in the current language (see [Progress](ia-progress.md)): **This week**, the
  headline (time spent writing and reviewing, with the last 8 weeks; it opens Time spent);
  **Practice** ("4 due" with Start practice, or when the next one is); **Your writing**: Words
  ("312 words you've written, 18 new this month"), Your conjugations ("12 of 40 forms used",
  Spanish and Catalan), Your patterns (top groups and a sparkline once there's enough);
  **Saved by you**: Phrasebook (count). Under Linguize the headline and the Phrasebook cover
  every language once, Practice has a row per language in use, and Your writing is one card
  per language, headed by its brand name and icon.
- **History**: the list in the current language (every language under Linguize), with a
  source dropdown above it when there is more than one source (see
  [One list of messages](history-flat.md)).
- **Settings** (gear): AI service and Second opinion; Checking (What to suggest); Writing
  preferences and Per-app voice; About you (What Linguize knows); **In other apps**:
  Selection menu, Accessibility button, Popup position; Chat (Open to chat); History.

## Before and after

| Entry | Before | After | Why |
|---|---|---|---|
| Chat | App start; Home row; History threads | App start (default); bottom bar; Open conversation on a chat message's saved check | A tab, so the Home row and the chat bar's house button are redundant |
| Conversation list | A drawer behind the chat bar's menu icon | The Chat tab's top level; a conversation opens above it with Back to the list; the drawer and its icon are gone | The list sat in the top nav and didn't read as part of the chat; a messaging app's list is its home |
| New conversation | The drawer's first row | The list's main button, at the bottom; the sheet has no language choice: the conversation starts in the app's language (under Linguize, the chat's start language) | The primary action of the list; one language selector |
| Language | Progress's Catalan/Spanish switch (Words and conjugations only); the chat bar's ES/CA button; Words' language chips; Your conjugations' switch; then a top-bar switch plus Write's picker, the new-conversation sheet's dropdown and History's language dropdown | One selector in the top bar of the four tabs: Linguize (Auto, the default), Catalanize, Castilianize; Progress, Chat, Notes and History follow it | The language is context for the whole app; several pickers disagreed about it |
| A conversation's language | Changeable from its bar, then the app's switch in its bar | Fixed once created; its bar has no selector | A conversation is in one language |
| Filters (History source, Words' Show, Sort and Count by, deck choice) | Chips, outlined or filled buttons, radio rows | One quiet dropdown ("Sort: Newest ▾") | Filters must not look like actions |
| Top bar | Chat's list: "Chat"; Write: the wordmark; Progress: "Progress"; History: "History"; a conversation: its own bar with the switch and the gear | Every tab: the wordmark, the selector, the gear; every other page: Back and its title, conversation included | One pattern; the bottom bar already names the tab |
| Write | The second tab | **Notes** (see [Notes](notes.md)); the launcher shortcut is Notes, "Write a note" | A place, like the other tabs; it holds notes |
| Try it | Home, top | Notes, top, as the editor (see [Notes](notes.md)) | The core "check this" job; merged with the Writing pad |
| Add an API key strip | Home | Notes (the chat has its own) | Where the first check happens |
| Writing pad | Home, own section | Removed; its drafts open in the Notes editor | Same job as Try it, with a second check experience ([Notes](notes.md)) |
| Fill the gaps | Home row; card row when text has gaps | Removed; every check fills [gaps](gaps-in-check.md); its saved page opens Notes | Part of the core check, everywhere |
| Help me reply | Home, own section; Understand card | Removed; saved pages open Notes | Interpretation, not part of the writing loop; another app does it |
| Understand a photo | Home, own section; share sheet | Removed, with the image share target; saved pages open Notes | Same: send the photo to a general assistant |
| Practice | Home, only while due | Progress, under the headline, always (Start practice, or when the next one is) | Pull, not push: visiting Progress is asking |
| Your patterns | Home card once worth showing; row above History | Progress | One place for stats |
| Your words | Home card; row above History | Progress › Words, in the current language | Merged with Vocabulary gaps: the words you know are a subset of all the words |
| Your conjugations | Home, own section | Progress, in the current language | |
| Vocabulary gaps | Home row | Progress › Words (Not yet filter, coverage in the header); its saved pages open Words | One list, not two |
| Phrasebook | Home, own section; star on the card | Progress › Saved by you, last; star unchanged | Things kept from your own writing |
| Recent (last five) and View all | Home | Removed; History is a tab | A preview of a page that's one tap away |
| History | View all on Home; Settings › History | Bottom bar; Settings › History unchanged | |
| Accessibility button setup | Home, bottom | Settings › In other apps | Set up once |
| Selection menu, Popup position | Settings › Checking, Settings › Popup | Settings › In other apps | Grouped with the accessibility button: how Linguize shows up elsewhere |
| Settings | Gear on Home; gear in the chat bar | Gear on all four tabs, at the same place; not on pages opened from them | |
| Open to chat | Settings › Chat: Chat or Home | Settings › Chat: Chat or Notes | Same preference, same stored value |
| Try it launcher shortcut (then "Write", now "Notes") | Opened the start page (the chat, by default) | Opens Notes above the start | It promised the editor |
| Practice's last button | Back to Home | Back to Progress | Where Back goes |
| What Linguize knows, Per-app voice, Second opinion | Settings | Settings | Set up, edited rarely |
| Card rows (Meaning, How it lands, Second opinion, voice chip, Teach me, star) | Card | Card | They act on the text in front of you |
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
History's Open conversation is `open(Chat).open(ChatThread(id))`. A language switch is
`afterLanguageSwitch()`: Back to the list if a conversation is open (no conversation shows
the selector now, so this only guards the rule). The Notes shortcut and a popup's Open
settings don't open a conversation. A saved `[lab_chat]` restores as the list. Notes is
saved as `notes`; `home` (its token as Home, then Write) still restores as Notes, so a saved
path or a pinned shortcut from before works. An old `[lab_chat, home, history]` restores as
`[lab_chat, history]`. The tokens of removed pages above Notes (`lab_gap_filler`,
`lab_reply`, `photo`) restore as Notes. `HomeActivity.notesIntent` asks for Notes. The
Notes shortcut uses it.

The bar a screen gets is `Screen.topBar(title)` in `HomeActivity.kt`: `TopBar.Root` for the
four tabs, `TopBar.Page(title)` for everything else. `AppNavigation` draws the selector and
the gear only on a root, and Back only on a page. A conversation draws the same
`AppNavigation` from `ChatTopBar`, because only it knows its title.

## What didn't change

- Feature internals, view models, prompts, History data, the check card and its rows.
- The selection-menu, accessibility and popup flows, and the share, tile and clipboard hosts.
- Each feature page and its title. (Their own language switches and pickers are gone: see
  [Language scope](language-scope.md).)
- What Linguize knows stays in Settings. It's something you edit, not a view of progress.
- There's no in-app page for the Quick tile. Android adds tiles from Quick Settings, and
  the tile's page explains that.
- `HistoryViewModel.recent` (and its tests) stays, though nothing shows Recent now.

## Limits

- A page Progress opened from a language's block under Linguize keeps that language after
  the app is recreated only as far as its view model survives; restored from scratch it
  shows its default.

- Switching tabs doesn't keep each tab's inner path. Coming back to Progress opens its top,
  not the pattern you were reading.
- Progress counts conjugations when it opens (on the phone, no model). That's the one
  view that reads the corpus there.
