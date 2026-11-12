# Gaps in every check

Write in the language you're learning and leave what you can't say in `[brackets]`, or just in
your own language. Every check fills those gaps, wherever you check: the selection menu, the
accessibility button, Chat's Send card and Notes.

> ¿Puedes **[pick me up]** a las 8? Tengo que **drop off** las llaves.
>
> ¿Puedes **recogerme** a las 8? Tengo que **dejar** las llaves.

This replaces the standalone Fill the gaps screen and card row. Stuck words are part of
writing, so the check that already reads every message handles them, and nobody has to pick
a different tool first.

## How it looks

Each gap comes back as a fix like any other: the bracketed or native phrase, the fill, and a
short reason ("Filled gap", "Written in English"). Accept, Undo, Replace all and Apply work as
for any fix, and Meaning translates the message with its gaps filled. Only the gap changes;
when grammar needs the word next to it, that word is part of the same change
(`a [the market]` → `al mercado`). Mistakes elsewhere are separate fixes.

## How it works

- `assets/lab_gaps_in_check.md` holds the rules. `llm/lab/GapsInCheck` adds it after the
  shipped `check_prompt.md` at request time, before the [How it lands](tone-check.md) rules
  and, in the chat, the [reply](reply-context.md) rules (`llm/lab/CheckComposition` fixes the
  order). The shipped prompt and schema are unchanged, and so is the user message: the answer
  is read by the shipped parser and placed by the edit engine.
- `CheckComposition.prepare` keeps the composed request, its compatibility hash and
  the base fresh-answer reader together in `PreparedCheck`. `Checker` sends the request
  through the provider's `generate` API and reads the raw answer with that base reader.
  The context's `promptHash` is
  `<shipped hash>-g<hash of the rules>` (after any reply suffix, before the tone suffix), so History never reuses an
  answer from a check without them. Preparing without the rules retains the shipped
  request bytes; `testModel` still uses the provider's plain base-check helper.
- In a check asked only for rewordings, fixes aren't shown, so the rules ask for fills as
  rewordings there.

### Why every check, not only when gaps are detected

A cheap detector can see brackets, and a short list of English words, but not a gap in
French, German or another native language, nor an English word that list doesn't know.
Gating would miss exactly the unmarked cases. The rules are about 600 tokens, small
next to the shipped prompt, and they also stop a mixed message from being read as
`wrong_language`. So they go with every check.

## Learning from your words

A filled gap is not evidence you knew the word.

- A fill you accept is an accepted fix, so the `check` corpus source marks its span as
  corrected, brackets or not. In a rewordings-only check, an accepted rewording whose source
  has a bracketed gap or English words is marked too (`CheckUtterances.corrected`).
- Words and Conjugations skip bracketed gaps, brackets and all, whatever happened to the fill
  (`core/corpus/WriterGaps`, used by `WordTokenizer` and `ConjugationMap.tokens`).
- Words leaves out common English words inside a corrected span: most likely a filled gap,
  so neither used nor tried.

## Limits

- One fill per gap. The old screen's alternatives are gone; Retry or a clarification on
  Meaning gives another.
- Unbracketed gaps depend on the model noticing them. Brackets are the reliable signal.
- Unaccepted, unbracketed native words count in Words as the writer's own, and only English
  ones are recognised inside corrected spans.
- The composed request is covered by offline contracts; dedicated live cases for gap
  quality are still absent.

## Tests

- `GapsInCheckCheckerTest`: composed request bytes, fills through Accept and Undo,
  rewordings-only checks, reply context and the base request without gap rules.
- `llm/lab/PreparedCheckContractTest` and `ComposedCheckContractTest`: fixed request
  and compatibility-hash fixtures, repair preservation and the base fresh parser.
  The [eval runner](../../eval/README.md) exports the same Kotlin preparation code.
