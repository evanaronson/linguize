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
   the thing to do and the rest as views, and one language switch for the views that
   depend on language.
5. **Nothing becomes unreachable.** Every experiment still has a path. Rarely used tools
   are a tap lower, not gone.
6. **Back is predictable.** The start destination is the root. Every other tab sits right
   above it, so Back from any tab returns to the start, and Back from the start leaves.

## The person's jobs

| Job | How often | What serves it |
|---|---|---|
| Talk and practise | Daily, the main reason to open the app | Chat (conversations, Practise with…) |
| Write or check one specific thing | Several times a week | Try it, Writing pad, Fill the gaps; card rows: How it lands, Say it in Catalan, Replying to, Hear it, Second opinion |
| Understand something someone sent, and answer it | Weekly | Help me reply, Understand a photo; share sheet; Understand copied text |
| See how I'm doing | Weekly | Practice, Your patterns, Words, Your conjugations, Phrasebook |
| Look back at what I wrote | Weekly | History (one row per message, any source) |
| Set up | Once, then rarely | Keys and model, languages, selection menu, accessibility button, popup, per-app voice, What Linguize knows, Open to chat, History switch |
| In another app, before sending | Many times a day, outside this app | Selection menu, accessibility button, Quick tile, launcher shortcuts, share |

## Structure

**Bottom bar: Chat, Write, Progress, History.** Shown on these four pages, hidden on the
pages they open and while the keyboard is up. The app starts on Chat, or on Write when
**Open to chat** is off (Settings, Chat).

- **Chat**: the conversation, with its own bar (conversations, language, Settings).
- **Write**: the add-key strip until there is a key; **Check your text** (Try it, with
  its card rows); **More ways to write**: Writing pad, Fill the gaps; **Read and reply**:
  Help me reply, Understand a photo.
- **Progress**: **Practice** (always shown here: "4 due" or "Nothing due"); **Your
  writing**, with a Catalan/Spanish switch: Words ("312 words you've written in Catalan":
  the words you've used and the common ones you haven't, in one list), Your conjugations
  ("12 of 40 forms used"). Opening one opens
  it on that language. **Fixes and phrases**, across languages: Your patterns (top groups
  and a sparkline once there's enough), Phrasebook (count). **Time**: Time spent (this
  week's total and a sparkline; see [Time spent](time-spent.md)).
- **History**: the list, with only a source filter above it (see [One list of messages](history-flat.md)).
- **Settings** (gear): AI service and Second opinion; Checking (What to suggest); Writing
  preferences and Per-app voice; About you (What Linguize knows); **In other apps**:
  Selection menu, Accessibility button, Popup position; Chat (Open to chat); History.

## Before and after

| Entry | Before | After | Why |
|---|---|---|---|
| Chat | App start; Home row; History threads | App start (default); bottom bar; Open conversation on a chat message's saved check | A tab, so the Home row and the chat bar's house button are redundant |
| Try it | Home, top | Write, top | The core "check this" job |
| Add an API key strip | Home | Write (the chat has its own) | Where the first check happens |
| Writing pad | Home, own section | Write › More ways to write | Same job as Try it, longer text |
| Fill the gaps | Home row; card row when text has gaps | Write › More ways to write; card row unchanged | |
| Help me reply | Home, own section; Understand card | Write › Read and reply; Understand card unchanged | Replying is writing |
| Understand a photo | Home, own section; share sheet | Write › Read and reply; share sheet unchanged | |
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
| Try it launcher shortcut | Opened the start page (the chat, by default) | Opens Write above the start | It promised Try it |
| Practice's last button | Back to Home | Back to Progress | Where Back goes |
| What Linguize knows, Per-app voice, Second opinion | Settings | Settings | Set up, edited rarely |
| Card rows (Meaning, How it lands, Say it in…, Fill the gaps, Hear it, Second opinion, Replying to, voice chip, Teach me, star) | Card | Card | They act on the text in front of you |
| Selection menu, accessibility button, Quick tile, Check and Understand shortcuts, share text, share photo, popup's app button | System | System | Unchanged; the app button and "Open settings" still open the app and Settings |

## Navigation model

`ui/home/Navigation.kt`. `Screen.tabs` lists the four destinations. A path starts at the
start destination; opening a tab replaces everything above the start with that tab
(`[Chat]`, `[Chat, Progress]`), and any other screen opens above the current one
(`[Chat, Progress, Patterns, Pattern, Saved check]`). `Navigation.tab` is the bottom bar's
selection. Saved state is the same list of tokens as before. Write keeps the token `home`,
so a saved path from before restores. An old `[lab_chat, home, history]` restores as
`[lab_chat, history]`. `HomeActivity.writeIntent` asks for Write. The Try it shortcut uses
it.

## What didn't change

- Feature internals, view models, prompts, History data, the check card and its rows.
- The selection-menu, accessibility and popup flows, and the share, tile and clipboard hosts.
- Each feature page, its title and its own language switch. Progress only sets the
  language before opening one.
- What Linguize knows stays in Settings. It's something you edit, not a view of progress.
- There's no in-app page for the Quick tile. Android adds tiles from Quick Settings, and
  the tile's page explains that.
- `HistoryViewModel.recent` (and its tests) stays, though nothing shows Recent now.

## Limits

- Switching tabs doesn't keep each tab's inner path. Coming back to Progress opens its top,
  not the pattern you were reading.
- Practice and Your patterns cover every language. They don't follow the switch.
- Progress counts conjugations when it opens (on the phone, no model). That's the one
  view that reads the corpus there.
