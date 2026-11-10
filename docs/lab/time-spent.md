# Time spent

How long you actually spend doing language work in Linguize: writing and reviewing your
messages, per message, per word and per change, by language and by week. It's the headline of
Progress. Nothing shows while it's being timed.

## The user moment

Someone learning by writing real messages wants to know whether the effort is going down and
where it goes: "I spent 40 minutes this week on Spanish messages, a third of it reviewing; I
take 4 s a word, and review takes about 6 s per change." It's evidence of practice, measured
from what they already do, not a timer they have to start.

## Assessment: what is measured, what is estimated

| | Chat | Selection menu, accessibility button |
|---|---|---|
| Writing | **Measured**: first keystroke that leaves text in the composer → Send | **Estimated**: words × your own measured chat speed |
| Review | **Measured**: a result on the card → Send | **Measured**: a result on the card → put back |

- **Review is measured everywhere, not ditched.** The card is ours in both places, so its
  time is observable; it's the part of the work Linguize causes, and the one the writer can
  reduce. Keeping it separate from writing answers "is checking costing me more than it
  gives?". It counts only while a result is on the card (an answer, or a question to
  clarify), not while waiting for the model or looking at a failure: waiting isn't language
  work.
- **Writing in other apps can't be seen**, so it's estimated, and always labelled so. The
  estimate is the message's words times the writer's own chat speed: in that language once
  there are 5 measured messages of at least 3 words (30 words in all), else all languages
  pooled (same thresholds), else a typical 4 s a word (about 15 words a minute, a careful
  writer composing on a phone in a second language). One- and two-word chat replies don't
  teach the speed: they're mostly reaction time. Estimates are computed when read, never
  stored, so old ones improve as the speed is learned.
- **Words** are what `core/words/WordTokenizer` finds in the text the writer wrote (the
  original, before any change), the same split as Words. Names count: they were typed too.
  Numbers, links and laughter don't.

### Active-time rules

- **Idle cap.** A pause between keystrokes counts at most 60 s; a card with nothing changing
  (accepting, undoing, answering) counts at most 2 minutes. A draft or card left open stops
  costing time.
- **Off screen.** Nothing counts while the chat isn't on screen (another tab, the app in the
  background, screen off: `onStop`), while the selection-menu popup is stopped, or while the
  accessibility popup is hidden behind the app it opened.
- **Committed messages only.** Only a message that was sent from the chat, or put back in
  another app, is recorded. An abandoned draft, a card closed without putting anything back,
  and Try it record nothing: there's no message to attribute it to and nothing in History to
  delete it with. (Drafts as a separate figure were considered and dropped: they'd be mostly
  noise from drafts left in the composer.)
- **Edit message** (or Back) on the review pauses review and resumes writing; the review time of that opening is
  kept for the same message. **Re-checks and clarifications** stay in review; their waiting
  doesn't count. **A failed check** counts as neither.
- **Not counted:** thinking before the first keystroke, and reading the partner's replies.
  Reading can't be told apart from leaving the chat open, the goal is the writer's own
  writing, and Understand is a different job. Pauses mid-draft are counted up to the cap.

### Metrics

- **Total**: measured (chat writing + all review) plus estimated (writing elsewhere), shown
  separately: this week (Monday first) and all time.
- **Per message**: (writing + review) / messages.
- **Per word**: measured chat writing time / its words. Nothing estimated enters it.
- **Per change reviewed**: review time / changes the card offered (fixes and rewordings in
  its last result), over messages whose card offered at least one. Per change *offered*,
  not per accepted: reviewing a change you decline is work too, and accepted counts would
  make careful writers look slow.
- **By language**, and the **last 8 weeks** as bars: measured solid, estimated lighter.

## How to use it

Progress shows, at the top as its headline (**This week**), for the app's current language ([Language scope](language-scope.md)), this week's total with a small chart; tap it for the page: This week,
All time (writing and reviewing, with the measured/estimated split), Averages, By language
(with what other apps' writing is estimated from), Last 8 weeks, and How it's counted.

## How it works

- `core/time/ActiveTimer.kt`: the one timer. Pure Kotlin, injectable clock; `start`, `touch`,
  `pause`/`resume` (the host's reason) and `visible` (on screen), `stop`. Gaps are capped.
- `core/time/DraftTimer.kt`: the chat draft's writing time. `ChatViewModel` calls it on
  each text change, Send, Edit message (or Back), a new conversation, and visibility (from `ChatScreen`'s
  lifecycle observer).
- `ui/time/CardTiming.kt`: the card's review time, owned by `CheckViewModel` (`timing`). It
  follows the card's state (counting only while it's `Done`); hosts only say when the card is
  off screen: `CheckActivity.onStart/onStop`, the accessibility popup's hide and return, and
  the chat. When the card closes, `CheckHistory.close` now returns the id of the session it
  is saving (null when it saves nothing), and one `TimeRecord` is written for it; the chat
  passes its `DraftTiming` through `dismissSent`.
- `data/time/TimeStore.kt`: SQLite `lab_time_spent`, one row per History session: session
  and chat message id, origin, language, `writing_ms` (null when not measured), `review_ms`,
  words, changes offered and accepted, time, and the rules version. Only measurements.
- `core/time/WritingEstimator.kt` (version 1) and `TimeReport.kt` derive everything on read.
- `TimeSpentViewModel` captures the requested language with each refresh. A newer refresh
  cancels the old one; an obsolete History read cannot sweep measurements or publish its
  report. Changing language clears the previous scope's report while the new one loads.
- **Deletion.** Deleting a conversation deletes its rows with its History sessions. Every
  time Time is read, rows whose session History no longer has (deleted from History, or
  History cleared) are dropped (`TimeSweep`); a row younger than 10 minutes is kept, since
  its session's write may still be landing.
  A failed History read skips the sweep, preserving measurements for a later retry.
- **History off.** Nothing is timed: the rows hang off History sessions, and someone who
  turned History off asked the app not to keep a record of their writing. The page says so.
- The History schema is unchanged.

## Limits

- Writing in other apps is an estimate; text pasted or written long before the check counts
  as if typed then.
- The idle caps are guesses; a writer who thinks for two minutes between sentences is under-
  counted. The accessibility popup can't tell when the screen goes off; the cap bounds it.
- In the accessibility popup, time on Understand copied text while a reviewed card waits
  behind it still counts as review (capped).
- A row whose History session is deleted stays up to 10 minutes, and until Time is next opened.
- Timings started before this build don't exist; totals start from now.
