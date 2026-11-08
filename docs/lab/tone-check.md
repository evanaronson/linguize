# How it lands

A Lab experiment (slug `tone-check`). Correct grammar can still land wrong. "Quiero
que me envíes el contrato" is correct Spanish and still reads as an order. A message
with no greeting can read as cold, and a word that is harmless in one variety can be
rude in another. The check answers "is anything wrong?" and "would a native say it
this way?". How it lands answers a third question: **how will this come across?**

## The user moment

The writer has checked a message, maybe accepted a few fixes, and is about to send it
to a landlord, a colleague or a neighbours' group. The grammar is now right, but they
are unsure about the tone: too blunt, oddly formal, too familiar? They want a native
reader's first impression, the words causing it, and a way to soften it that still
sounds like them. They do not want to be told that casual writing is wrong.

## How to use it

- **On the check card** (selection menu, accessibility button, and Try it on Write), a
  reviewed check has a footer row **How it lands** under **Meaning**. It opens a page
  for the text as it stands now: the original with the changes you have accepted.
- **On Write**, under Try it, a **How it lands** button reads the text in the box
  without checking it first.

The page shows:

1. **The overall reading.** *Should land well*, *Could land differently* or *Could land
   badly*, with a one or two sentence summary and the variety it was read as
   ("Read as Spanish (Spain)").
2. **Three scales.** Warmth (cold to warm), directness (indirect to blunt) and
   formality (casual to formal), each 1 to 5. Screen readers hear one phrase per
   scale ("Directness: 5 of 5, from indirect to blunt").
3. **What shapes it.** Exact words from the message and what each does for the reader.
4. **Good to know.** Up to three pragmatic or cultural notes.
5. **Other versions.** Up to two, each with a short aim ("More polite", "Warmer") and
   what changed. **Copy** copies it. **Use this** makes it the text that Apply puts
   back. The page then shows *Using this version* with **Undo** and **Apply**, and the
   footer row says *Using another version*. In Try it, **Use this** replaces the text
   in the box.

## How it works

- `app/src/main/assets/lab_tone_check_prompt.md` and `lab_tone_check_schema.json` are
  this feature's own prompt and structured-output schema. The shipped check prompt
  and schema are unchanged.
- `llm/lab/LandingPrompt` builds the request (system prompt, the message and the
  writer's native language as JSON data, the schema) and reads the answer.
  Structural problems fail as an invalid reply. A detail that can't be trusted is
  dropped and the rest is kept: a quote that isn't in the message, a version that
  changes nothing or repeats another. Scales are kept within 1 to 5. A message that
  lands fine gets one optional version at most.
- `LandingChecker` (root) uses the same provider, model, key and HTTP clients as
  checks, through `ProviderClient.generate`, like Understand copied text does.
- `ui/tonecheck/LandsController` runs one reading at a time, drops an answer for text
  that has since changed, and keeps a few readings in memory while the card is up,
  so going back and forth doesn't ask again.
- `ui/tonecheck/LandsPage` is the page, the footer row and the Try it entry.
  `ResultCard` calls it with a few lines. `CardActions.howItLands` carries what it can
  do in each host.
- Using a version: `CheckViewModel.useVersion` records the chosen text together
  with the card state it was chosen on. `workingText` returns it while that state is
  unchanged, so accepting, undoing or checking again quietly drops it. Every host
  already puts back `workingText`, so no host changed. History records the version as
  the final text, as for any applied check. Try it is never recorded.

## Respecting the writer

The prompt reads the message as written and describes it. It doesn't grade it. It
prefers the lower risk level when unsure, says casual writing that suits the
context is fine, and asks versions to keep the meaning, facts, emoji, length,
register and tú or usted, changing only what the aim needs.

## Limits

- It is one model's guess at a typical reader. Relationships, regions and people vary,
  and the page says so.
- It sees only the message, not the conversation or who it is for.
- Readings are not saved. Closing the card forgets them.
- A version you use is not checked again. Check it if you edit it further.
- Like every model feature, it needs an API key and a connection, and it costs one
  request each time it is opened for new text.

## Tests

- `llm/lab/LandingPromptTest`: the request carries this prompt and schema, plus parsing,
  dropping untrusted details, the one-version rule for fine messages and invalid replies.
- `LandingCheckerTest`: settings, key and native language used; failures before any
  request; transport errors.
- `ui/tonecheck/LandsControllerTest`: caching, stale answers, retry, close.
- `ui/tonecheck/ChosenVersionTest`: a chosen version is put back until the card changes.
- `ui/tonecheck/LandsCardTest`: the rendered card, from opening the page to using,
  applying and undoing a version, plus system Back and retry.
