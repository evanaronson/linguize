# Your patterns

Your patterns turns saved checks into a short list of the fixes that keep coming back:
written accents, ser and estar, prepositions, gender agreement, Catalan "tinc que", and
so on. Each group shows how often it appeared, whether it shows up in fewer or more recent
checks, and the actual changes it came from. Tap a change to open its saved check.

## The moment it serves

The writer is not mid-message. They have a minute on the bus, open Linguize, and wonder
"what do I keep getting wrong?" History answers "what did I check", one check at a time.
Your patterns answers the next question without a model, a key or a connection, and
without changing how checking works. It serves the "slowly needing the check less"
moment in the [Lab README](README.md) while keeping *evidence first, learning later*.

## How to use it

- **Home**: once there are at least 3 counted checks and one group seen in 2 of them, a
  "Your patterns" card appears under History, naming the top groups. It disappears again
  if deletions leave too little.
- **History**: a "Your patterns" row sits above the list.
- **Your patterns page**: recurring groups, each with a count ("5 times in 3 checks"), a
  trend phrase and a small chart over time. Below: "Explain my patterns", "Not grouped"
  and "How this works".
- **A pattern's page**: the count, a description, a larger chart with dates, whether the
  grouping is a fact or Linguize's guess, the most common before/after pairs, and every
  example with its date and saved reason. Each example opens its saved check.
- **Explain my patterns** (optional, needs a key): sends the group names, counts and up
  to 4 distinct examples per group to the chosen service, and shows a short summary with
  one tip per group. It can be asked again or deleted.

## How it works

Code:

| Piece | Where | Layer |
|---|---|---|
| Word diff, folding, edit distance | `history/patterns/WordDiff.kt` | history (pure Kotlin) |
| Rules that group one change | `history/patterns/PatternRules.kt` | history (pure Kotlin) |
| Counting, trends, report | `history/patterns/PatternAnalyser.kt` | history (pure Kotlin) |
| Reading History through its APIs | `history/patterns/PatternEvidence.kt` | history |
| Explanation prompt and reply | `llm/lab/PatternsPrompt.kt`, `assets/lab_patterns_prompt.md` | llm |
| Lab-local explanation store | `data/lab/PatternNotes.kt` (`lab_patterns` preferences) | data |
| Composition, invalidation | `PatternsLab.kt` | root |
| Screens and Home card | `ui/patterns/` | ui |

### Evidence

`patternEvidence` pages through History with `page()` and reads each check with
`detail()`, the same eligibility as the History list: closed, applied, with changed final
text. It reads the newest 400 checks at most. A failed read shows an error rather than
partial patterns.

Counting rules (`PatternAnalyser`):

- Only suggestions whose recorded decision is `accepted` count: accepted on the card and
  put back. Ignored, undone, copied, retired and superseded rows are not evidence of a
  mistake (see [History](../history.md#decisions-and-outcomes)).
- A check whose decided answer was reused from another check is left out, so a reused
  answer is not counted twice.
- When the same text was checked more than once, only the newest check counts.
- Each change uses the target language of its own attempt.
- A group needs 2 distinct checks to be a pattern. Groups seen once, and fixes no rule
  recognised, go under "Not grouped".

### Grouping

`PatternRules.classify(from, to, why, language, kind)` is deterministic, offline and
ordered from most to least certain:

1. **Exact** (a fact about the text): the before and after differ only in punctuation and
   spacing, only by adding ¿ or ¡ (Spanish), only in capitals, or only in accents and
   other diacritics (compared after Unicode normalisation and folding).
2. **Words** (inferred): a word-level LCS diff, then rules over the changed words: Catalan
   *tenir que* → *haver de* / *cal*; Catalan elision (*la amiga* → *l'amiga*); a ser form
   swapped with an estar form (Spanish and Catalan); *por* / *para*; gender pairs
   (*el/la*, *-o/-a*, Catalan *-/-a*); singular and plural; articles only; prepositions
   only.
3. **Reason** (inferred): keywords in the recorded reason, in English, Spanish or Catalan
   (subjunctive, ser/estar, gender, agreement, accents, prepositions, articles, verb
   forms, spelling, punctuation, capitals).
4. **Words** (inferred): a single word replaced by one spelled almost the same is spelling.

Everything else is `other`. A rewording (`natural`) only counts when steps 1–3 match:
sounding more natural is not a mistake.

Every example keeps its basis. The UI says "Grouped by the change itself" only when every
example is exact, and otherwise "Grouped by Linguize. It can be wrong."; a pattern with
mixed bases labels each example.

### Trend and chart

The trend compares the *share* of counted checks containing the group in the older and
newer halves of the counted checks (by count), so checking more often doesn't read as
"more". It needs 8 counted checks and 3 checks with the group; a change of 15 percentage
points is "fewer" or "more", otherwise "about as often". The page says it is based only on
saved checks. The chart splits the time between the first and last counted check into 6
equal spans.

### Derived data, sources and deletion

The report is never stored. It is rebuilt after every History write (`revisions()`),
when a patterns screen opens, and on retry. A deleted check is gone from it on the next
build. Each report records:

- `analyser` (`patterns-v1`), bumped whenever rules or counting change;
- `sources`: every counted session, the attempt, and the suggestion ids it contributed;
- each example's session, suggestion, attempt, and basis;
- `fingerprint`: a hash of the analyser version and the exact rows used.

The only stored derived data is the optional explanation, in the Lab-only preferences
file `lab_patterns`. It records its summary and tips, the fingerprint, analyser version,
prompt version (a hash of the prompt asset and schema), provider, model, time, number of
checks, and the session ids whose examples it used. `explanationStatus` decides on every
build:

- a different analyser version, or any source session no longer in History: deleted;
- same sources plus newer checks: kept, marked "Newer checks aren't included";
- same fingerprint: current.

`PatternsLab.watch()` runs for the life of the process and applies this rule after every
History write, including Delete, Delete all and Undo deletions made from other screens, so
a saved explanation never outlives the checks it used. It reads nothing while no
explanation is saved. History itself is never written.

### Explain my patterns

`PatternsPrompt` has its own asset (`lab_patterns_prompt.md`) and schema, and reuses the
existing provider clients' `generate`. The request carries the writer's own language,
up to 8 groups, and up to 4 distinct examples per group (before, after, reason, each cut
to 300 characters). It never carries session ids, dates, app names or whole messages. The
reply must name only groups that were sent, once each, or nothing is saved.

## Limits

- The groups are heuristics. A verb ending *-o → -a* can be grouped as gender; a
  reason's keyword can be misleading. That is why inferred groups are labelled.
- It reflects only checks that were saved: applied, with History on. Checks made with
  History off, copied, or not put back are missing. It is not an error rate or proof of
  progress (see [What the records can support](../history.md#what-the-records-can-support)).
- Rules cover Spanish and Catalan; other languages get only the exact and reason rules.
- Reads up to 400 newest checks; older ones are left out.
- A deletion waiting for Undo stays in the report until it is committed.
- The explanation is written by the chosen model and can be wrong; it is sent only when
  the writer taps the button.

## Tests

- `history/patterns/PatternRulesTest`: every group and basis, language gating,
  normalisation, rewordings, precedence, determinism.
- `history/patterns/PatternAnalyserTest`: counting rules, sources, deletion and
  fingerprint, ordering, merged pairs, per-attempt language, trend and buckets.
- `history/patterns/PatternEvidenceTest`: paging, limits, failed and vanishing reads.
- `PatternsLabTest`: explanation saving, what is sent, invalidation on delete/clear,
  outdated state, analyser change, the process-wide watcher, failures.
- `llm/lab/PatternsPromptTest`: request shape and caps, reply validation, version.
- `ui/home/PatternsNavigationTest`: saved navigation state.
