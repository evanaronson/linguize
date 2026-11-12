# Progress: information hierarchy

Progress shows recorded writing and activity. The standalone Practice drills and
scheduler have been removed; no replacement practice system is introduced.
See [Home and navigation](home-ia.md) and [Language scope](language-scope.md).

## The person's jobs

| Job | What serves it |
|---|---|
| See whether I am keeping at it | This week's writing and reviewing time, with recent weeks beside it |
| Explore my writing | Words, Your conjugations and Your patterns |
| Find a phrase I kept | Phrasebook, under Saved by you |
| Understand a number | How it's counted on each analysis page |

## Hierarchy

**This week** is the headline: time spent writing and reviewing, a message count,
and the last eight weeks. The card opens Time spent. Under Linguize it totals every
language; a chosen language limits the figure to that language.

**Your writing** contains three views of the same recorded writing:

- Words: words written and new words this month.
- Your conjugations: forms used, for Spanish and Catalan only.
- Your patterns: recurring accepted changes, with a summary when there is enough evidence.

Under Linguize there is one card per language in use, headed by its brand name and
icon. Each row opens its page in that language. A selected language shows one card.

**Saved by you** contains the Phrasebook, once for the whole tab. It is a collection
the writer chose to keep; it is separate from the derived analysis views.

## Analysis pages

Words, Your conjugations, Your patterns and Time spent share one shape:

1. A header naming the language, since these pages have no selector.
2. A summary of the page's own figures.
3. Its detail and source examples.
4. How it's counted, in muted paragraphs at the end.

Empty pages explain what evidence is missing and how it arrives. Loading states
name what is being read. Charts and legends use the shared Progress controls.

## Implementation

- `ui/progress/ProgressScreen.kt`: `progressLayout` orders `Headline`, `Views` per
  language and `Saved`; `progressViews` chooses the rows each language supports.
- `ui/progress/ProgressKit.kt`: shared scope headers, figures, loading/empty states,
  explanations and chart legends.
- `ui/time/TimeSpentScreen.kt`: `TimeHeadline`, which opens Time spent.
- `HomeActivity`: scopes a selected view with its language block or the app's language.
- Old saved `practice` and `scoped:<language>:practice` routes restore to Progress.

## Limits

Time records activity, not a language level. It excludes model waiting and is
estimated for external writing. Word and conjugation figures describe the eligible
History sample, not every draft written on the phone. External recording retains
its duplicate protection, and Notes remains an unrecorded drafting space.

Under Linguize, each language block's Patterns row still uses the available
single-scope report. Opening the page loads the selected scope.
