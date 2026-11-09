# Second opinion

A Lab experiment: ask a second model for the same check and see, change by change,
where the two agree.

## The user moment

The writer is on the check card, about to accept a change they aren't sure about. Model
suggestions are fallible, and Linguize's value is trust. A change two independent models
both make is easier to accept. A change only one model makes, or where they disagree, is
worth a second look. This matters most when the writer can't judge the change themselves,
which is often why they checked.

## How to use it

1. Have a key for both Gemini and OpenAI, or choose a second model from the same service
   in **Settings › Second opinion** (under the Model card).
2. Check text as usual: selection menu, accessibility button or the editor on Write.
3. Under the suggestions, **Second opinion** offers **Ask OpenAI** (or Gemini, or the
   chosen model). Tap it.
4. The card adds:
   - a short summary: "Google Gemini and OpenAI agree", "… differ" or "… found nothing to change",
     with counts ("2 agreed, 1 only Gemini, 1 disagreed");
   - one row per change with a badge: **Both agree**, **Only Gemini**, **Only OpenAI** or
     **Disagree**, and for a disagreement what each says ("Gemini says estàs",
     "OpenAI says està");
   - the same badge in a change's explanation when the writer taps it, with what the
     other model did ("OpenAI keeps it as written", "OpenAI says està").

Accepting, undoing and Replace work as before. The second opinion stays while the writer
accepts changes and disappears when the card shows another answer (a re-check,
clarification, another language). The button then offers it again.

### Settings

**Settings › Second opinion** has these choices:

- **Other service** (default): the service checks don't use, with the model settings use
  for it.
- **Gemini** or **OpenAI**: that service, then a model from its key's model list, or
  "Same as checks".
- **Off**: the card offers nothing.

A note under the choices says what the card will do with the current settings, or why
it can't: no key for that service, or the same model checks already use. The choice is
kept in the Lab's own preferences file, `lab_second_opinion`. It doesn't change the
app's settings, keys or history.

## How it works

- **Same check.** `SecondOpinionController` (in `ui/secondopinion`) takes the card's text,
  pinned language, the writer's clarifications and the exact `CheckContext` the check ran
  with. It runs `Checker.check` again with only the provider and model swapped. The prompt,
  schema, punctuation, judgments, native language and writing profiles are the same. The
  existing provider clients and the same answer parsing and interpretation are reused, so
  this feature brings no prompt of its own. Repeating the shipped check is the point.
- **Comparison** (`core/opinion/Opinions.kt`, pure Kotlin). Both answers are already turned
  into edits anchored to the writer's original text by `interpret`, so they can be
  compared without a model:
  1. Within a section (fixes, rewordings), edits with the same span and replacement agree.
  2. Edits whose spans touch and that turn that stretch into the same text agree, though one
     included a neighbouring word ("cami" vs "el cami" becoming "camí"). Text is compared in
     composed Unicode form, as alignment does.
  3. What's left is grouped where spans overlap or meet. A group from one answer only gives
     one "Only" item per edit. A group from both agrees if the stretch comes out the same
     (one answer's two edits against the other's one) and disagrees otherwise.
  4. A fix in one answer and the identical rewording in the other agree.
  Each item keeps both answers' edit ids, so the card can badge its own suggestions.
  `shown` trims whole words both versions share, so rows read "cami becomes camí".
- **Both fine** comes from each answer's `looksGood`. A second answer that is unclear or
  thinks the text is in another language is reported as such. A failure shows the usual
  failure title and hint, with Retry.
- **History.** Nothing changes. The controller only reads the card's check and never
  calls `CheckHistory`. A test asserts that the session's attempts and the store are
  untouched.

## Hooks into shared files

- `CheckViewModel`: owns one `secondOpinion` controller; the factory passes the app's plan.
- `CardActions`: an optional `secondOpinion`, set by `CardActions.of`.
- `ResultCard`: wraps the suggestions page in `WithSecondOpinion`.
- `Explanation`/`HighlightedText`: an optional badge slot in the explanation panel.
- `SettingsScreen`: one row and one `SettingsPage` entry.

## Limits

- It's two requests: the second costs the writer another call on their own key and
  takes as long as a check.
- Agreement isn't correctness. Two models can share a mistake, and a model may disagree
  with the shipped prompt's style choices.
- Only the first answer's suggestions can be accepted. A change only the second model
  makes is shown but can't be accepted from the card. Re-check with that model to use it.
- Changes either answer had to withhold (incomplete answers) aren't compared. The summary
  says so.
- Matching is structural: two rewordings of one phrase that differ in wording count as a
  disagreement, even when they mean the same thing.
- Second opinions aren't saved, and aren't available on saved checks in History.
