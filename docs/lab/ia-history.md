# History tab: information hierarchy

The History tab was already in good shape (see [One list of messages](history-flat.md)):
one row per message under day headers, a quiet source dropdown, swipe to delete with Undo.
This pass changes order and a few details, not features.

## The person's jobs, in order

1. **Look back at what I wrote and what got fixed**, recent first. ("What did I get wrong
   in that message to my landlord?") Weekly, sometimes daily.
2. **Open one message** to see the before, the after, and each change with its reason
   (and Teach me, or star it for the Phrasebook).
3. **Narrow it down** to one place (WhatsApp, Chat). The language is already set at the top.
4. **Go back to the conversation** a chat message came from.
5. **Tidy up**: delete one message (swipe, Undo).
6. **Manage the data**: export everything, delete everything. Rare.
7. **Understand why it's empty**: nothing saved yet, History off, or nothing in this
   language or from this source.

## Inventory (before)

**Tab, top to bottom**

| # | Element | Prominence |
|---|---|---|
| 1 | Header "45 checks since 5 Oct" (all languages, all sources) | Section header, first thing on the page |
| 2 | "History is off. New checks aren't saved." with Open settings (when off) | Card |
| 3 | Source dropdown "All sources ▾" (when there's more than one source among all loaded rows) | Quiet dropdown |
| 4 | Empty filter note ("No messages from WhatsApp yet.") | Card |
| 5 | Day headers (Today, Yesterday, 5 Nov) over cards of rows | Section headers |
| 6 | Row: "14:02, WhatsApp, Catalan (auto-detected)" / first line of the text / "1 fix, 1 accepted" | List row; swipe or screen-reader action to delete |
| 7 | Footer: loading, Retry, or Load more checks | Card segment |
| 8 | Export and Delete all | List card, last |
| 9 | Snackbar: Check deleted, Undo; Deletion incomplete, Retry | Transient |
| — | Empty History: header "No checks saved", then Export (disabled) and Delete all (enabled, of nothing) | — |

**Saved check (Back + "Saved check")**

| # | Element |
|---|---|
| 1 | Header with date and time; card: source (app, or "Chat · Weekend plans"), target and detected language; Open conversation (chat messages) |
| 2 | Before: the text, and any failure or "unclear" note |
| 3 | Meaning: the reading, Assumptions row (opens the read-only Assumptions page), Your clarification |
| 4 | How it lands (when saved) |
| 5 | After: the final text, or what happened instead |
| 6 | Suggestions: each change with Accepted / Not accepted; tap for the reason, Teach me, Phrasebook star |
| 7 | Delete this check (confirm dialog) |

## Target hierarchy

**Tab.** Primary: the rows under day headers, starting at the top. Secondary: the source
dropdown, right above them, offered only when it can narrow something. Tertiary: the data
controls and the count they act on, together, at the end. Notices (History off, empty) sit
where the list would start.

- The count "45 checks since 5 Oct" describes everything saved, in every language. Under
  Catalanize it sat over a Catalan-only list it didn't describe, and it pushed the content
  down with a second header right above "Today". It's what Export and Delete all act on, so
  it moves under them, as their caption.
- The source dropdown offers only sources with messages in the current language. Under
  Castilianize with only WhatsApp in Spanish, there's nothing to choose, so it isn't shown.
- An empty filter names the language when there is one ("No messages from WhatsApp in
  Catalan yet."): "No messages from WhatsApp yet." was false when WhatsApp had Spanish ones.
- Under one language, rows drop the language from their meta line: every row would repeat
  "Catalan". Under Linguize they keep it, since rows mix languages.
- Empty History says what will appear ("Chat messages and checks you put back in other apps
  appear here.") instead of only "No checks saved", and doesn't offer Export or Delete all of
  nothing. With History off, its notice is the explanation.

**Saved check.** Primary: the evidence, in one cluster: Before, After, then the changes
that turned one into the other. Secondary: the readings of the message, Meaning (with
assumptions and the clarification) and How it lands. Tertiary: Delete this check. The
details card (when, where, language, Open conversation) stays on top: it's short and it's
the context for everything under it.

## Current vs target

| Element | Current | Target | Why |
|---|---|---|---|
| "N checks since …" | Header above the list | Caption under Export and Delete all | It counts everything; it's the scope of those actions, not of the list |
| Source dropdown | Sources among all loaded rows | Sources with messages in the current language (plus the chosen one) | Don't offer a filter that can only show nothing |
| Empty filter text | "No messages from WhatsApp yet." | "No messages from WhatsApp in Catalan yet." under a language | True in every state |
| Row meta | Time, source, language | Time, source; language only under Linguize | Don't repeat what the top bar says |
| Empty History | Header "No checks saved", Export (off), Delete all | "Chat messages and checks you put back in other apps appear here." | Say what goes here; no actions on nothing |
| Export, Delete all | Last on the tab | Last on the tab, only when something is saved | Unchanged position |
| Saved check order | Details, Before, Meaning, How it lands, After, Suggestions, Delete | Details, Before, After, Suggestions, Meaning, How it lands, Delete | Before and After were split by readings; the job is "what did I get wrong" |
| Meaning, How it lands | — | Moved as blocks, internals untouched | Another agent redesigns them for the card |

## Considered and rejected

- **Moving Export and Delete all.** They stay where they are (the end of the tab); Settings
  keeps the History switch. Out of scope for this pass.
- **Calling everything "messages".** Rows say "Open check", the snackbar "Check deleted",
  the footer "Load more checks", the page "Saved check". A row is the saved check of one
  message, so both words are true; renaming means changing shared strings for no gain.
- **Moving Open conversation down** next to Delete. It's the one way onward from a chat
  message and belongs with the source it names.
- **Suggestions before After** (Before, changes, After). The changes explain the
  difference, so they read best after both texts; on the card they're also below the text.
- **Search, app icons, inline highlighting on rows, collapsible days.** New features or a new
  expand pattern; not justified by the jobs above.
- **Hiding day headers under a filter.** They still help scanning.

## Files

`ui/history/HistoryScreen.kt` (order, empty state, data caption), `HistoryList.kt`
(`filterChoices(rows, selected, language)`, `emptyFilterResource`), `RecentSection.kt`
(`SessionRow(showLanguage)`), `SessionScreen.kt` (order),
`res/values/strings_lab_ia_history.xml`. Tests: `HistoryLanguageTest`,
`SessionAssumptionsScreenTest.beforeAfterAndChangesComeFirstThenTheReadings`.

## Limits

- The count caption still counts every language and source; that's what Export and Delete
  all cover.
- Sources come from the loaded pages, as before.
