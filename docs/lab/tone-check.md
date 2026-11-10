# How it lands

A Lab experiment (slug `tone-check`). Correct grammar can still land wrong. "Quiero que me
envíes el contrato" is correct Spanish and still reads as an order. A message with no
greeting can read as cold, and a word that is harmless in one variety can be rude in another.

Before sending, the writer asks one question: **is this OK to send?** It has four parts, and
one check answers all of them, in one request, on one card, everywhere (Chat, the selection
menu, the accessibility button and Notes):

| Part | Where it shows |
|---|---|
| Is anything wrong? | Fixes |
| Would a native say it? | Rewordings |
| Does it say what I mean? | Meaning |
| How will it come across? | Meaning: how it lands ([one section](meaning-lands.md)) |

How it lands used to be a separate button, request, loading screen and page, with whole
"warmer" or "more polite" versions to swap in. That was a second check to remember to run,
and a version swap that bypassed the edits the writer had reviewed. Now it is part of the
check.

## The user moment

The writer is about to send a message to a landlord, a colleague or a neighbours' group.
The grammar is now right, but are they too blunt, oddly formal, too familiar? They want a
native reader's first impression and the words causing it, without being told that casual
writing is wrong, and without a second wait.

## How it looks

- **The line.** The card's **Meaning** section ([Meaning](meaning-lands.md)) shows the
  reading as its second line, under the meaning: *Lands well · friendly, a bit direct*, *Could
  land differently · polite but cold*, or *Could land badly · reads as an order*.
- **The details.** Opening Meaning shows them as its last part, **How it lands**: the
  text as it will read after the fixes, with the words that shape it most highlighted; three
  scales, warmth (cold to warm), directness (indirect to blunt) and formality (casual to
  formal), each 1 to 5; and up to three short notes ("Requests are usually softened with
  *podrías*."). Screen readers hear one phrase per scale ("Directness: 4 of 5, from indirect
  to blunt") and the highlighted words as a list.
- **Tone help is rewordings.** When it could land differently or badly, the check offers
  the smallest local changes that help, as ordinary rewordings with the tone effect as their
  reason: "Warmer greeting", "Softer request". They are accepted, undone and applied like any
  other change, so what goes back is exactly what the writer reviewed. A message that already
  lands well gets none for tone. Casual is not wrong.
- **No reading, no line.** An answer without one (an older answer, a reused one from before
  this, a model that left it out, or an unclear or wrong-language message) shows the card as
  before.
- **History.** A saved check whose answer has a reading shows it, read-only, in its Meaning section.

## How it works

- **One request.** `assets/lab_tone_in_check.md` holds the rules, and
  `assets/lab_tone_in_check_schema.json` the `landing` property. `llm/lab/ToneInCheck` adds the
  rules after the shipped instructions and the property to the shipped schema at request
  time. `check_prompt.md` and `check_schema.json` keep their bytes; a test pins them.
- **Composition order** (`llm/lab/CheckComposition`): shipped prompt, then the
  [gap](gaps-in-check.md) rules, then the tone rules, then in the chat the
  [reply](reply-context.md) rules, with the `Replying to:` line in front of the shipped user
  message. The three add about 8,000 characters to a 21,000-character prompt.
- **The schema extension.** `landing` is added last to `properties`, `required` and
  `propertyOrdering`, as `anyOf` an object or `null`: OpenAI's strict structured output needs
  every property listed, and null keeps it optional in meaning. The object has `overall`
  (`lands_well`, `could_land_differently`, `could_land_badly`), `summary` (a few words in the
  writer's own language), `warmth`, `directness`, `formality` (1 to 5), `drivers` (up to four
  exact quotes) and `notes` (up to three).
- **Reading it.** The answer is read with the shipped contract (`ComposedPromptClient` and
  `ReplyingClient` take the shipped prompt as their `reader`), so `landing` is never required.
  `ToneInCheck.landing` then reads it from the same raw answer, leniently: it is judged on the
  text after the answer's fixes (`corrected`, or the text when nothing needed fixing); a quote
  not in that text or an overlong note is dropped; scales are kept within 1 to 5; anything
  structurally wrong means no reading, never a failed check. `Checker` puts it on
  `CheckResult.Reviewed.landing`, for fresh answers and for answers reused from History.
- **History.** The raw answer, `landing` included, is stored as History already stores raw
  answers; no schema change. The prompt hash gains `-t<hash of the rules and schema>` after
  any gap suffix, so answers with and without the tone rules are never reused for each other.
  A saved check reads `landing` from its decided attempt's raw answer (`SavedLanding`).
- **The card.** `ui/tonecheck/LandingDetails` draws the details; the Meaning section
  (`ui/card/MeaningLands.kt`) puts the line in its header and the details last. No controller,
  no loader, no page.

## Respecting the writer

The rules describe, they don't grade. They prefer the milder reading when unsure, say casual
writing that suits its register is fine, offer tone rewordings only when it doesn't land
well, keep the writer's meaning, facts, emoji, length, voice and *tú* or *usted*, never add
promises, apologies or new content, and never make tone a fix.

## Limits

- One model's guess at a typical reader. Relationships, regions and people vary; the details
  say so.
- Outside the chat it sees only the message, not the conversation or who it is for.
- It reads the text after all the fixes. If the writer declines a fix, the reading may not
  match what they send exactly.
- The rules are a Lab prompt with no eval cases yet, and they add to every check's prompt and
  answer (a few hundred output tokens).

## Tests

- `llm/lab/ToneInCheckTest`: shipped bytes pinned; the composed schema; rules and hash;
  reading with and without `landing`, judged on the corrected text, untrusted details dropped.
- `llm/lab/CheckCompositionTest`: the order of gaps, tone and reply; the user message; the
  hash in order; prompt growth.
- `ToneInCheckCheckerTest`: the one composed request; answers with, without and with a bad
  `landing`; tone rewordings through the edit engine; reuse; a chat check with all three.
- `ui/tonecheck/LandingDetailsTest`: the rendered card's line in Meaning, its details opening
  in place, nothing without a reading, highlighting.
- `ui/card/ReplyContextCheckTest`: `workingText` is the accepted changes only.
