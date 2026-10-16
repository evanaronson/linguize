# Checker corruption audit — 13 October 2026

This audit reviews provider answers and the actual production Checker/edit/Accept engine. Its most serious finding is that a plausible final correction can conceal invalid individual Accept actions. Contiguous text and a single sentence do not establish semantic atomicity.

## Method

- 100 distinct synthetic passages: 50 Catalan and 50 Spanish, each originally two or three sentences.
- Seed `20261013`; 396 traceable random mutations across ten families: letter insertion/deletion/transposition, space insertion/deletion, punctuation insertion/deletion, word substitution, line breaks and accent removal.
- Every damaged passage is requested in its named language and in Auto Detect, for 200 requests. Settings are moderate punctuation, both judgments and English explanations.
- The shipped prompt and response schema are used. Saved answers are parsed and replayed through production `Checker.check`, history reuse and the edit engine. Both previews, every individual Accept, Accept all, Undo and acceptance order are recorded.
- Every returned answer and every offered action receives a manual review. Clean pre-mutation text provides context; random word substitutions can legitimately change meaning, so it is not an exact-output oracle.

Corpus SHA-256: `b0b464aa36cbd4458bd1caf8ca84a88cc9ac4c4e6ab95ef65ebbac786d6de075`.

Prompt/schema SHA-256: `314febcd08ea23a6c3320be314bb9dad3e21ad4988c831692747bc2300b1717a`. Production baseline: `c953fd0445e87cc54f9e6ea1bf79d924c925924f` (build 124).

## Completed results

All 200 requests returned a verdict, all 200 were replayed, and all 200 were manually reviewed. The final evidence has no remaining provider errors.

| Answering model | Answers |
| --- | ---: |
| `gemini:gemini-3.5-flash-lite` | 186 |
| `gemini:gemini-3.5-flash` | 8 |
| `gemini:gemini-3.6-flash` | 4 |
| `gemini:gemini-3.1-flash-lite` | 2 |

77 requests have manually identified engine findings. 115 have at least one engine or language-quality flag. These are affected request counts, with repeated examples of the same failure family; they are not counts of unique bugs or a model accuracy score.

51 answers arrived beyond the app's 30-second call limit. They were collected with the diagnostic timeout and their language/edit behavior was reviewed, but the app would have timed out on those attempts.

The first pass produced 47 answers and 153 HTTP failures. Resuming only failed requests increased completed answers to 135, 184, 186, 196 and finally 200. The default model eventually returned 429s; some alternatives returned 503s. A listed `gemini-2.5-flash` target returned 404 on generation. Available alternatives were checked against the live model catalog. Fallback records preserve observed failures instead of hiding them.

Initial replay checks flagged only two reconstruction mismatches. Manual examination found many failures that those checks missed. The added diagnostics now also expose dependent word splits, bundled orthographic repairs and natural digit changes; they still do not establish semantic atomicity for every phrase.

Full counts and model provenance are in `audit_summary.json`. Every request's findings and review status are in `manual_review.json`.

## Reproduced failures

| Finding | Evidence | Why it matters |
| --- | --- | --- |
| A single word repair is fragmented into dependent actions | `ca-035:auto`: `micaabans → mica abans` becomes an insertion of `mica ` and a separate `micaabans → abans` action. Each individual Accept duplicates or removes a word. | A correct Accept all preview does not prove that suggestions can be accepted independently. |
| Alignment borrows text from a neighboring word | `sp-036:Spanish`: repairing `Revisarelos docum:entos` maps `docum → los` and `:entos → documentos`. | The suggested source/replacement pair describes the wrong change; individual acceptance corrupts the draft. |
| Separate spelling repairs share one fix | `ca-024:auto`: `pasti:lla despres de → pastilla després de` combines the colon repair and the accent on another word. `sp-033` similarly joins `dej:apré` and `tenida`. | One decision hides two independently acceptable corrections. |
| Numeric formatting becomes destructive edits | `sp-012:auto`: `2130 → 21:30` produces natural actions that individually yield `212130` or `30`. Accepting both restores `2130` with no colon. | Digits change, the count reports rewordings, and the intended formatting is still missing. |
| A spacing repair disappears behind link protection | Both modes of `ca-005`: the model returns the correct sentence, but the engine retains `alsofà.Si` and produces `al alsofà.Si`. | Ordinary sentence text resembles a domain during an intermediate edit. Fix reconstruction fails. Link safety needs to be preserved while resolving the alignment/spacing interaction. |
| Mandatory orthography remains wrong | `sp-003`: `Calientala` remains without the required accent (`Caliéntala`). `ca-012` generates malformed verbs such as `L'avísaré`/`Li avisiaré`. | Corrected text can still be incorrect, or introduce new errors. |
| Inserted line breaks survive correction | `sp-005` keeps a synthetic LF inside `Si \nllamas...` after other repairs. | The original input-string complaint remains reproducible in model output. |
| Explicit facts change without a corresponding assumption | `sp-028` changes `mañana` to `por hoy`; `ca-021:Catalan` changes the first-person future to `Provareu`, while its English meaning still says “I”. | A purported wording/correctness improvement changes time or speaker. |

The full ledger also flags missing grammar repairs, misleading explanations and questionable stylistic preferences. These quality flags require linguistic judgment. They are not all confirmed independent bugs, and counts of affected requests are not an accuracy score.

## The user's obligation-and-verb screenshot

The reduced case in `user_atomicity_case.json` is additional evidence outside the 100-passage corpus. `tinc que usar → he de fer servir`, explained as “More natural obligation and verb,” combines correcting the obligation construction with changing the valid verb `usar`.

Expected behavior: a correctness fix such as `tinc que usar → he d'usar`, preserving the valid verb; no obligatory verb-preference rewording. Any independently justified lexical alternative must be a separate decision. A reason containing “and” is evidence in this case, not a universal validator: a conjunction alone cannot prove how many decisions an edit contains.

The existing period/sentence guard addresses cross-sentence rewrites. The current naturalness grouping can still join adjacent changed words and short function words into one span, so it cannot establish that a phrase contains only one semantic transformation.

## Model independence and reproducibility

The corpus, review criteria and engine checks do not depend on a model ID. `--provider`, `--model` and ordered `--fallback PROVIDER:MODEL` targets select adapters. Gemini and OpenAI share the same replay checks. Each answer retains its actual provider/model and the requested primary target. Failures are kept when a fallback succeeds.

Fallback is for failed requests, not unfavorable judgments. A bad correction stays visible in the audit. To compare model quality, run the same full corpus into separate reply files; a fallback sample is not a balanced comparison.

The live snapshot contains Gemini answers; cross-provider fallback was also verified with stubbed Gemini/OpenAI adapters. No live OpenAI run was made. This corpus does not cover wrong-language negative controls, every punctuation mode, settled-answer interactions or physical device writeback.

Saved replies and production-engine outputs live in `baseline/`; `manual_review.json` contains one entry for every request. Review notes are bound to the exact provider/model, prompt/corpus hashes, model verdict and rendered engine output; changing any of these requires a fresh review. `confirmed_engine_failures.json` preserves specific reproduced actions. The baseline replay and derived artifacts are legacy, **unbound** evidence: their rows do not record the inputs consumed by replay, so the default report gate rejects them. Preserve those artifacts as recorded. Follow the [offline replay instructions](README.md) to produce separate, newly bound current-engine evidence from the frozen answers, then review the new outputs. The same guide documents explicit `--legacy-unbound` inspection, which labels the historical pairing as unverified.

The replay records violations and continues so that every case can be examined. A successful replay job means evidence capture completed; it does not mean the suggestions passed atomicity. The additional model-independent diagnostics flag dependent word splits, bundled orthographic fixes and natural edits that change digits.

Live requests use a 90-second diagnostic timeout to collect late replies. The shipped app has a 30-second call limit. Answers over that limit are reported separately and are not ordinary successful app checks. Transient errors, repeated attempts and selection of fallback cases also make these timings unsuitable as a normal latency benchmark.

## Focused corrective work

1. Repair alignment and grouping so a spacing, word or numeric repair remains independently acceptable and never borrows neighboring content. Validate individual acceptance against the intended repair, not only against the edit's own replacement string.
2. Reject natural suggestions that hide correctness repairs, change explicit facts/numbers or combine independent decisions. Suppress a suggestion when safe independent edits cannot be established. Sentence boundaries remain an additional constraint.
3. Resolve spacing reconstruction while retaining protection against newly introduced links.
4. Re-run all frozen provider answers through the corrected engine, then repeat the live corpus to assess model-side issues. Keep linguistic preferences separate from mechanically reproduced invariant failures.

This change adds audit tooling and evidence. Production atomicity fixes remain open.
