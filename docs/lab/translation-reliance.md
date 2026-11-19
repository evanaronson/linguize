# Translation reliance

How often you needed a chat reply's translation, and whether that's going down. Shown on
Progress, per language, as a **Translations** row and its page.

## The user moment

In Chat, a tap on a partner reply flips it to its translation in your own language
([Chat](chat.md)). The owner asked: "Am I relying on the translation too much? How many
messages needed it, and by needed I mean not an accidental tap." They want to see it over
time, to know whether they're improving. Nothing is shown in the chat itself: it's measured
quietly, like [Time spent](time-spent.md).

## What "needed" means

A reply **needed its translation** when the translation stayed readable for at least
**2.5 seconds** in one showing:

- **Readable** means the translation's text is showing (not "Translating…" or a failure), the
  chat is on screen (the app in the foreground, the screen on: the same `ON_START`/`ON_STOP`
  the chat's writing timer uses) and the reply's bubble is on screen (laid out in the
  conversation's list, even partly; not scrolled away).
- **One showing** runs from the tap that flips the reply to the tap that flips it back.
  Leaving the app, the screen going off, leaving the conversation or scrolling the bubble away
  **pauses** it; coming back resumes it. Flipping back short of 2.5 s ends it without
  counting, and the next flip starts again from zero, so quick taps never add up.
- **Once per reply.** A reply counts at most once, however often it's flipped, in this
  session (`TranslationDwell`) and in storage (the write only fills an empty `needed_at`).
- Time is a **monotonic clock** (`System.nanoTime`), never wall time; only the moment it
  became needed is stored as wall time, for the weeks.

**Why 2.5 s.** An accidental tap is undone within about a second: the bubble's tint changes,
the writer notices and taps back; even a slow correction stays under two seconds. Reading a
typical short reply (8 to 15 words) in one's own language takes about 2 to 4 s (at 200 to
250 words a minute, plus finding the line). 2.5 s sits above any accidental flip and at the
low end of real reading. The cost is on the safe side: a glance at a three-word reply may go
uncounted, but a mistaken tap never counts. Tuned in `TranslationDwell.NEEDED_MS`.

## What counts as a reply

The denominator is every partner reply (the opener too) that **arrived with its own
translation** after this version. A reply saved before it, or one without a translation of
its own (the fallback opener when there's no key; a reply whose translation was missing), has
no record: tapping it fetches a translation as before, but it isn't measured. **No backfill**:
older replies can't be measured, because nobody timed them. Only replies **the writer
answered** count (a writer message follows them in the conversation): an unanswered reply
says nothing yet about whether its translation was needed. Answering one in the original is
the success case. A reply becomes unanswered again when Edit message removes the answer.

## How to use it

Progress, in each language's card (under **Your writing** with a language chosen; under the
language's brand name under Linguize), last row: **Translations**, with
"Translation needed for 12 of 40 replies (30%)", or "How often you need a reply's
translation" until a reply is measured. It opens the **Translations** page, in that language:

- **Summary**: "Translation needed for 12 of 40 replies (30%)". The percentage shows from 10
  replies; below that, "Translation needed for 3 of 6 replies".
- **Trend**, one plain line, only with enough data (the last 28 days and the 28 before, each
  with at least 20 replies): "20% in the last 4 weeks, down from 45% the 4 weeks before",
  "… up from 20% the 4 weeks before", or "30% in the last 4 weeks, about the same as the 4
  weeks before" (within 10 points). Otherwise: "A trend shows once there are more replies".
- **Encouraging, honest:** "Needing a translation is part of learning. A new topic or a new
  partner can raise it for a while." Up is said as plainly as down.
- **Last 8 weeks**: one bar per week, 0 to 100%, the share of that week's replies that needed
  a translation, by when the reply arrived (Monday first). A week with fewer than 5 replies
  has no bar, only the flat baseline ("A week needs 5 replies for a bar"). It's the bar style
  of Time spent's weeks, drawn with `Canvas`; no chart library.
- **How it's counted**, ending the page as on every Progress page.
- Empty: "No replies measured yet", "Replies in the chat count from this version on. Tap one
  to see its translation."

Accessibility: the summary and trend are read as one; the chart has a text description
listing every week ("Share of replies whose translation you needed, by week from Sep 21:
40%, too few replies, …, 20%"); the axis labels and legend dot are decoration.

## How it works

- `core/chat/TranslationDwell.kt`: the pure rule. `shown(id, showing)`, `visible`,
  `onScreen(ids)`, `drain()` (newly needed ids) and `remainingMs()` (when to look again). An
  injectable monotonic clock.
- `ChatViewModel`: `flip` (and the end of a fetch) tells it whether the reply shows its
  translation's text; `visible` (the chat's lifecycle) and `onScreen` (from `ChatBody`'s
  `LazyListState.layoutInfo.visibleItemsInfo` keys, empty when the list leaves) tell it what
  can be read. After each event it drains, writes `ChatStore.markTranslationNeeded` on the
  app scope, and wakes itself after `remainingMs`.
- `data/chat/ChatStore.kt`, the chat's own database `lab_chat`, **version 6**: table
  `reply_reads` (`message` primary key referencing `messages(id)` `ON DELETE CASCADE`,
  `conversation`, `language`, `at`, `needed_at`), created with `CREATE TABLE IF NOT EXISTS`.
  A row is written in the same transaction as a partner message that has a translation, with
  the conversation's language. `replyReads()` reads them all.
- **Deletion.** The row hangs off its message, so it goes with it: deleting a conversation
  (cascading to its messages) and Edit message's rewind (deleting the message and everything
  after it) remove the measurements of the replies they remove, in the same transaction.
  Nothing else holds them; History is not involved and its schema is unchanged.
- `core/chat/TranslationReliance.kt`: the report (summary, weeks, trend) from the rows,
  computed on read, for one language or all. Thresholds: `MIN_SUMMARY_REPLIES` 10,
  `MIN_WEEK_REPLIES` 5, `MIN_TREND_REPLIES` 20, `SAME_POINTS` 10.
- `ui/reliance/`: `TranslationRelianceViewModel` (one read shared by Progress's rows) and
  `TranslationRelianceScreen`; Progress's `ProgressView.Translations`, `Screen.Translations`
  (saved as `lab_translation_reliance`, scoped by a language block like the other pages).

## Limits

- A reply needed only to check one word, read in under 2.5 s, isn't counted.
- A translation left showing while the writer looks away still counts once 2.5 s pass on
  screen. The threshold can't tell reading from staring.
- A bubble counts as on screen when any part of it is laid out, even under the review panel's
  edge.
- A reply counts once answered, whenever that is; its week is the week it arrived.
- The trend compares two 4-week windows; a harder topic or a new partner moves it as much as
  learning does. The page says so.
- Fetched translations of older replies (through Understand) are never measured.
