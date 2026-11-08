# Fill the gaps

Write the message in Spanish or Catalan, leave the words you don't know in your own
language, and Linguize fills only those gaps.

> Hola, ¿puedes **[pick me up]** a las 8? Tengo que **[drop off]** las llaves.
>
> Hola, ¿puedes **recogerme** a las 8? Tengo que **dejar** las llaves.

## The moment

The writer is mid-sentence in WhatsApp and gets stuck on one phrase. Today they leave the
app, translate the whole sentence somewhere else, and paste back a translator's voice in
place of their own. Or they guess, and the check then has to repair a wrong word without
knowing what they meant. Fill the gaps keeps their sentence and their voice, and asks for
help only where they asked for it. The writer still writes the message; Linguize finishes it.

## How to use it

- **Home:** tap **Fill the gaps** below Try it. Write in the box, put gaps in
  `[brackets]` (or just mix in English), pick a language or leave Auto Detect, and tap
  **Fill the gaps**. The result shows each fill highlighted, then a **Filled in** list with
  your own phrase, the fill, a short note, and **Other ways to say it**. Tap an
  alternative to use it. Then:
  - **Use text** puts the result in the box, for more editing.
  - **Check it** puts it in the box and runs the normal check card below it.
  - **Copy** copies it.
- **The check card (selection menu and accessibility button):** when the selected text has
  bracketed gaps, or looks like it mixes in English, the card shows a **Fill the gaps**
  footer row ("2 gaps in brackets", "Some words look like English"). The check still runs
  as usual, so nothing is lost by ignoring it. Tapping it swaps the card for the fill:
  - **Put back** closes the card and replaces the selection with the filled text.
  - **Check it** runs the normal check on the filled text, in the same card. Apply then puts
    back the filled text with any accepted fixes, and closing without accepting anything
    still puts back the filled text.
  - **Copy**, or the back arrow to return to the check.
  - The language picker above the card fills again in another language.
- **Try it on Home:** the same footer row appears on its card, and opens Fill the gaps with
  that text.

### Why offer, not auto-run

Brackets are a clear signal, so the card always offers the fill when it sees them. It does
not run the fill by itself: the writer may have meant the brackets, and running a second
request nobody asked for would cost time and quota. Correct text never gets the row
("not nagging is a feature"). Unbracketed English is detected with a short list of common
English words that are not Spanish or Catalan words (so not "me", "he", "has", "on"), and
only when they are a minority of the message: a message fully in English is a translation,
not a gap.

## How it works

- `core/lab/Gaps.kt`: bracket detection, the offer heuristic, and `GapFill`, which places
  the model's fills on the source text. The result text is **assembled from the source**:
  every character outside a fill is the writer's own, by construction.
- `llm/lab/GapPrompt.kt` with its own assets, `assets/lab_gap-filler_prompt.md` and
  `assets/lab_gap-filler_schema.json`. The request sends the message as data, the target
  language (or "detect"), the writer's own language for notes, the bracketed phrases, and
  the writing profile (register, variety) for the target language.
- The model returns, per fill, `original` (the exact source text it replaces, brackets
  included), `filled`, `note` and up to three `alternatives`. Parsing rejects an answer
  whose quotes are not in the message in order, that leaves a bracketed gap unfilled, or
  whose fill still has brackets. That shows as the usual "invalid reply" failure, with Retry.
  A quote may include one neighbouring word when grammar needs it (Spanish "a" + "el" is "al").
- `GapFiller` (root) reuses the existing provider clients' `generate` transport, the
  selected provider and model, and the encrypted key, like Understand.
- `ui/gaps/`: `GapFillViewModel` (request, retries, alternatives, and what a popup puts
  back), `GapFillCard`, `GapOffer` (footer row and the popup's switch between check and
  fill), and `GapFillScreen` with its Home entry.

## Limits

- Fills are not saved. History is unchanged; a check run with **Check it** from a popup is
  recorded like any other check of that text.
- Only the gaps change. Spelling or grammar mistakes in the writer's own words are left
  for the check, which is why **Check it** is there.
- Unbracketed detection only knows English. Other native languages work with brackets,
  and the model can still fill unmarked phrases when asked from Home.
- A single unbracketed English word is not offered on the card (too easy to confuse with a
  name or loanword); brackets always are.
- The writer's native-language words are sent to the model provider they set up, as with
  every check.
