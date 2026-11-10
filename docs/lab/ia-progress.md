# Progress: information hierarchy

The Progress tab and every page it opens. The tab was a list of five features in two groups,
under a line of meta text. It's now one headline, one action, then the views, with the
Phrasebook kept apart, and the pages it opens share one shape. See
[Home and navigation](home-ia.md) for the app's structure and
[Language scope](language-scope.md) for what the language selector does here.

## The person's jobs, in order

| # | Job | How often | What should serve it |
|---|---|---|---|
| 1 | **See how I'm doing at a glance.** "Am I keeping at it?" | Every visit; the reason to open the tab | One headline at the top, readable without a tap |
| 2 | **Know what to practise next, and do it.** | Most visits, when something is due | Practice, right under the headline, with one button |
| 3 | **Dig into one area.** "Which words don't I use?", "Do I ever use the subjunctive?", "What do I keep getting wrong?" | Weekly | Words, Your conjugations, Your patterns: one card of views, each row with its own number |
| 4 | **Find a phrase I kept.** | Now and then, mid-writing | The Phrasebook: a collection, not a stat |
| 5 | **Understand a number.** "Where does this come from?" | Rarely | "How it's counted" at the end of every page |

## Inventory (before)

**Tab** (language chosen; under Linguize the middle three groups repeat once per language,
each under its brand name and icon):

| Position | Element | Prominence |
|---|---|---|
| 1 | Note: "Everything here is for Catalan. Switch the language at the top for another." (Linguize: "One section per language…") | Muted line, the first thing on the page |
| 2 | **Practice** header; row "4 due" / "Nothing due", "Your own fixes, about a minute"; the row opens Practice | Same row style as everything else |
| 3 | **Your writing**: Words ("312 words you've written in Catalan"), Your conjugations ("12 of 40 forms used"; under a chosen non-Spanish/Catalan language a row that opens "covers Spanish and Catalan") | Rows |
| 4 | **Fixes and phrases**: Your patterns (top groups, sparkline), Phrasebook (count) | Rows |
| 5 | **Time**: Time spent ("42 min this week", sparkline) | Row, last; under Linguize below every language's block |

**Pages it opens:**

| Page | Top | Explanation | Empty | Loading |
|---|---|---|---|---|
| Practice | a run: "1 of 5" and Stop; else a message card (title medium) | none | "No fixes to practice" card | "Reading History" card |
| Words | header with the language, coverage card with stat tiles | "How it's counted" | title + hint card | spinner + text |
| Your conjugations | an intro note, then "Tenses and persons" with "12 of 40 forms used" inside the grid card | "How this works", body text in full contrast | title + hint card | spinner only |
| Your patterns | an intro note ("From 34 saved checks since …") | "How this works" | title + hint card | spinner only |
| Time spent | "This week" totals card | "How this is counted" | title + hint card | nothing |
| Phrasebook | search, "3 phrases saved" with Add phrase | none (a collection) | one muted line, no title | nothing |
| Pattern, word, cell pages | their own summary card | n/a | n/a | n/a |

Charts: Patterns and Time use proof-coloured bars, Words a proof-coloured line; Your
conjugations' months were grey bars. Words and Time each had their own copy of a legend.
Filters were already dropdowns (Words' Show, Sort, Count by, Deck).

**What was wrong.**
- No headline. The first thing was a sentence about the language, which the selector right
  above already says. "How am I doing" needed reading five rows.
- Practice, the one action, looked like every view, and its row opened a page that only
  repeated "Nothing due" when nothing was.
- Under Linguize, Time (the only number for every language) sat at the very bottom, under
  every language's block, and each language had its own Practice section between views.
- Phrasebook was grouped with Your patterns as "Fixes and phrases", but it's a collection
  you build, not a view of your writing.
- Pages Progress opens have no selector, but only Words said which language it showed.
- Three names for the same section ("How this works", "How it's counted", "How this is
  counted"), four empty-state looks, three loading looks, two legends.

## Target hierarchy

**Primary: the headline.** *This week*: the time spent writing and reviewing, big, with
"Writing and reviewing, 12 messages" and the last 8 weeks as small bars beside it. The whole
card opens Time spent. Why time and not coverage or words: it's the one number that is the
same for every language and every source (chat and other apps), it is weekly, it already has
a trend, and it's cheap to read. Coverage depends on the deck and the language, and words
are per language; each keeps its own number on its own row, one line lower. Under Linguize
it's the total for every language, above the languages' blocks, so the page reads
"overall, then by language".

**Primary: the action.** *Practice* right under it. One row per language: "4 due", "Your
own fixes, about a minute" and a filled **Start practice** button. With nothing due there's
nothing to open, so the row isn't a target and says when the next practice is ("Next
practice tomorrow"); with no fixes yet, it says how they arrive. Under Linguize the rows sit
together in one card, one per language ("Catalan", "4 due, about a minute", Start practice),
so every action is above every view.

**Secondary: the views.** *Your writing*: Words ("312 words you've written, 18 new this
month"), Your conjugations ("12 of 40 forms used"), Your patterns (top groups and sparkline).
Three lenses on the same writing: the words, the verbs, the fixes. Each row carries its own
figure, so the card is a second glance at progress. Under Linguize there is one such card per
language, under its brand name and icon. Your conjugations is only listed for Spanish and
Catalan, chosen or not; before, a chosen other language got a row whose page only said
"covers Spanish and Catalan".

**Tertiary: what you kept.** *Saved by you*: Phrasebook, last, once for the whole tab.

**The pages.** One shape for Words, Your conjugations, Your patterns and Time spent:

1. A header naming the language ("Catalan", or "All languages" under Linguize). The page
   has no selector, so it says what it shows.
2. A summary card of stat tiles: a big figure over what it counts.
   - Words: coverage, then words, lexemes, lemmas, written, this month (unchanged).
   - Your conjugations: "12 of 40" forms used, verbs; the "counted from what you typed"
     caption inside.
   - Your patterns: patterns, checks counted; "Since Oct 3".
   - Time spent: this week's total, "this week, 12 messages", and its writing/reviewing
     split.
3. The detail, as before.
4. **How it's counted**, last, muted paragraphs in one card. Practice gets one too (where
   items come from, when they come back, deletion) on its non-run states.

Nothing yet is the same card everywhere: a short title ("No patterns yet", "No verb forms
yet", "No phrases yet", "Nothing timed yet") and what to do. Reading is a small spinner with
what's being read. Every bar chart is proof-coloured with hairline empties, and both charts
with a legend use the same one.

## Current vs target

| Element | Before | After | Why |
|---|---|---|---|
| Scope note | First line of the tab | Removed from the tab; each page it opens names its language in its first header | The selector says it on the tab; the pages have no selector |
| Time spent | Row in **Time**, last (under Linguize below every block) | **This week** headline card, first; tap opens the page | The one at-a-glance number for every language |
| Practice | A row like the views; tap opens the page, even with nothing due | Second; a **Start practice** button when due; otherwise the next practice, not a target | The main action looks like one; no dead taps |
| Practice under Linguize | One section per language, between the views | One card, a row per language, above all views | Actions together, at the top |
| Words row | "312 words you've written in Catalan" | "312 words you've written, 18 new this month" | The language is in the selector or block heading; the month's growth is progress |
| Your conjugations row | Shown for any chosen language | Spanish and Catalan only, as under Linguize | A row that opens "not covered" doesn't serve a job |
| Your patterns | Under **Fixes and phrases** | In **Your writing** with Words and Your conjugations | Three views of the same writing |
| Phrasebook | Under **Fixes and phrases**, once per language under Linguize | **Saved by you**, last, once; its page groups by language | A collection you build, not a stat |
| Child page top | Words only named its language | Every page: language header, then a summary of stat tiles | One shape; the page says what it shows |
| Explanations | "How this works" ×2, "How it's counted", "How this is counted"; Practice none | "How it's counted" everywhere, same card, Practice included | One name, one look |
| Empty states | Four looks (Phrasebook: a bare line) | One: title and hint | Consistency |
| Loading | Spinner, spinner with text, a card, nothing | Spinner with what's being read | Consistency |
| Conjugations months | Grey bars | Proof bars, hairline empties | Same as every other chart |

**Considered and rejected.**
- *Moving the Phrasebook to Notes.* A phrasebook is a writing aid, and Notes is where you
  write, so it's a reasonable home. But Notes is one editor and its notes; a second kind of
  list there would compete with it, and Notes is another tab's to change. In Progress it's
  last, apart, and one tap away, and the star on the card is still where phrases are saved.
- *Coverage or "words this month" as the headline.* Both are per language and per deck;
  under Linguize there'd be one per language, which is not one headline.
- *A row of three stat tiles (time, words, forms) on the tab.* It repeats the rows below.
  Each row already carries its figure.
- *One mixed-language Practice run under Linguize.* Practice and its scheduling are per
  language; a run that switches language every item is harder, and a row per language
  costs one line.
- *Showing the language in the child pages' top bar as a subtitle.* The cleanest place, but
  the top bar is shared and not this tab's to change. Noted for the top-bar owner.
- *Collapsing each language's block under Linguize.* Would be a new expand pattern; the
  blocks are short now (one card each).

## Implementation

- `ui/progress/ProgressScreen.kt`: `progressLayout(current, found)` is the tab's order as data
  (`Headline`, `Practice`, `Views` per language, `Saved`), and `progressViews(language)` the
  rows a language's card lists. `PracticeRow` is the action row.
- `ui/progress/ProgressKit.kt`: `ScopeHeader`, `StatTile`/`StatRow`, `EmptyCard`,
  `PageLoading`, `HowItsCounted`, `ChartLegend`, `ProgressCardPadding`, used by every page.
- `ui/time/TimeSpentScreen.kt`: `TimeHeadline` replaces the Time row.
- `ui/practice`: `PracticeState.nextDueIn(language)`; `nextPracticeLine` shared with the tab.
- `ui/patterns/PatternsViewModel.kt`: `PatternsState.language`, so the page can name it.
- `HomeActivity`: Progress's `onOpen` scopes with the block's language, or the app's
  (`scopeView(view, block ?: language)`), so the Phrasebook opened once under Linguize shows
  every language after a block opened another.
- Strings: `strings_lab_ia_progress.xml`. Tests: `ui/progress/ProgressLayoutTest`.

No data model, storage, prompt or feature behaviour changed.

## Limits

- Under Linguize, a language block's Your patterns row still shows the general summary
  (the report is built for one scope at a time).
- The headline counts only time; it doesn't say whether the writing got better. Nothing in
  the records can say that honestly (see [Your patterns](patterns.md)).
- Practice's own run page doesn't name its language; its sentences do.
