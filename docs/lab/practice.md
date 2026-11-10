# Practice

Short drills built from the writer's own fixes. The best exercises are your own
mistakes: Practice shows a sentence as you wrote it, with the mistake, and asks
for the fix. It runs on the phone, offline, with no model and no API key.

## The moment

Not the second before sending: the minute after, or any idle minute later. The
writer has already seen a fix and put it back. Seeing the same mistake again a
day later, then a few days later, is how "the check" slowly becomes unnecessary
for that mistake. A run is 5 to 10 items and takes about a minute.

## How to use it

- Progress shows **Practice** under its headline: "N due" with **Start practice**, or when the next practice is, for the app's current
  language: only its items are due and drilled ([Language scope](language-scope.md)). Nothing is pushed
  anywhere else: Progress is where you go when you want to practise.
- Tap it. Each item shows **You wrote** with the mistaken words underlined in
  proof vermilion, as on the check card.
  - **Type the fix** for words and short phrases. Check compares locally.
  - **Which one is right?** for changes that are hard to type or see: a deletion,
    punctuation, spacing or capitals alone, and replacements longer than four
    words or 40 characters. The two sentences swap places between reviews.
  - **Show fix** reveals the answer; it counts as a miss.
- After an answer: Right, Almost (with what to look at, for example
  "Almost: missing accent on “después”"), That's how you wrote it, or Not
  quite. Then the change ("despues becomes después"), the saved reason and
  weight meters, and the corrected sentence.
- A missed item comes back once at the end of the run. The run ends with
  "4 of 5 right the first time" and when the next practice is due.

## Where items come from

`history/practice/PracticeItems.kt` reads History through `HistoryStore.page` and
`detail` (the same visible, applied checks History lists, up to the newest 500):

- Only suggestions with decision `accepted` and kind `fix`. Undone, ignored,
  copied, retired and superseded suggestions are skipped: the writer did not
  apply them. Rewordings are skipped as items (they correct no mistake), but an
  applied rewording in the same sentence is applied in the context.
- Rows whose span no longer matches the saved text are skipped.
- The item is the sentence containing the fix (`core/Sentences`), cut to about
  90 characters either side when longer than 240. The writer's *other* applied
  fixes in that sentence are already made, so only this mistake differs.
- A fix that cuts into a word is widened to the whole word; a bare insertion
  takes the next word ("Voy casa" asks for "a casa").
- The same fix (same words, same replacement, same language, ignoring case) made
  in several checks is one item. Its id is a suggestion id: one that already has
  scheduling state if any, otherwise the oldest, so ids stay put as checks are
  added.

## Answer checking

`core/practice/Answers.kt`, pure Kotlin:

1. NFC, typographic apostrophes and quotes made plain, spacing collapsed.
2. Equal: right. Capitals are ignored unless the fix was about capitals. The
   whole corrected sentence is accepted as well as the replacement words.
3. Equal to the original mistake: "That's how you wrote it".
4. Equal without punctuation: almost, punctuation.
5. Equal without accents (á, è, ñ, ç, l·l): almost, and when exactly one word
   differs it is named: missing accent, no accent, or check the accent.
6. One letter away (5+ characters) and closer than the original was: almost,
   spelling.
7. Anything else: not quite.

## Scheduling

`core/practice/Schedule.kt` is a small SM-2. Right goes 1 day, 3 days, then the
interval times the ease (2.5 to start, 1.3 to 3.0, +0.05 per right answer). Almost
grows the interval by 1.2 and lowers the ease by 0.15. A miss or Show fix brings
the item back in 10 minutes, resets the streak and lowers the ease by 0.2.
Intervals are capped at 180 days. Only the first answer in a run counts.

`core/practice/Deck.kt` picks what is due: overdue reviews first, then new items
(most recent check first), at most 10 new items a day so a long History doesn't
arrive as one wall. A run takes up to 10 due items; when fewer than 5 are due, the
soonest upcoming reviews fill it out.

## Storage and deletion

Scheduling state lives in `lab_practice.json` in the app's files
(`data/practice/PracticeStore.kt`): suggestion ids, review times and ease, no
text. Items themselves are rebuilt from History whenever History changes
(`HistoryStore.revisions`). After a complete read, state for ids that no longer
exist is dropped, so deleting a check, or Delete all, removes its items and their
state. A failed History read changes nothing. Removing Lab removes the file.

## Limits

- Acceptance is not proof of a mistake: a fix the writer applied may have been
  a model error. Practice drills what was applied, nothing more.
- One expected answer per item. A different correct fix is "Not quite"; the
  reason and the corrected sentence show what was expected.
- Only the newest 500 checks are read; older ones lose their state.
- A deleted check's item disappears on the next History change, which happens
  after the list's Undo window closes.
- Reasons are shown in the language they were saved in.
