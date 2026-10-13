# Checker repair plan

Status: proposed implementation plan. Based on the [completed corruption audit](../eval/fuzz/REPORT.md) and source at `95a62610e47e7dc5650e0d0901f4b0f1037deb09`. This document changes no production behavior.

## Outcome and scope

Every offered Accept action must perform one independently meaningful repair or rewording. A plausible Accept all preview is insufficient: the audit found engine failures in 77 of 200 requests, with many repetitions of a few underlying problems.

Fix those causes while reducing the code that constructs and repairs edits. Each implementation PR must identify the faulty behavior, the code removed, the simpler replacement, and the regressions that justify keeping it. Delete superseded paths in the same PR. A falling line count alone is not success; preserve necessary behavior and avoid compressed or obscure code.

Use the existing core, card, and evaluation tooling. No new framework, app-wide refactor, model-specific exceptions, extra model judging service, or runtime provider fallback is part of this repair.

## 1. Replace faulty edit construction and remove its workarounds

Start with the frozen provider answers. This isolates engine failures from model variation.

The current flow in [Edits.kt](../app/src/main/java/com/evanaronson/linguize/core/Edits.kt) repeatedly groups, expands, merges, tidies, and maps spans. Some steps compensate for earlier ambiguous alignment; others let display needs change the meaning of an edit.

| Existing code | Required cleanup |
| --- | --- |
| `group`: deletion/spacing expansion into the next changed or nearest unchanged word, followed by overlap merging | Replace with complete repair units. Delete expansion that borrows unrelated content merely to make a highlight visible. Keep display context separate from the replaced text. |
| `spans`: moving deleted space operations to change which word receives a deletion | Remove this corrective pass when the replacement alignment handles the covered contraction/deletion cases directly. |
| `bridgeWords`, `PHRASE_WORDS`, and adjacency-based natural grouping | Stop using nearby words or a vocabulary list as evidence that changes are one decision. Remove the phrase-joining heuristic. |
| `mergeWithinFixes` | Remove automatic joining of independent rewordings to resolve mapped overlap. Correct the mapping or withhold the conflicting suggestion. |
| `located` plus fallback diff plus re-alignment of the resulting natural spans | Consolidate edit construction into one defined path. Model change lists are untrusted evidence; do not keep a second permissive path that invents broad rewordings when placement fails. |
| `reasons` / `matches`: source-only and substring matching | Remove reason attachment that can describe a different transformation. Use an exact verified match or the existing generic explanation. |
| `Alignment.closeSpelling`, `elisionPair`, `tidy`, and `greetingPreference` | Review against the replacement path. Delete superseded special cases; retain a helper only for a demonstrated contract. Do not grow the greeting blacklist. |

Implement one accountable sequence: derive repair units, validate their scope, map positions, then publish edits. Keep one owner for each transformation. Choose the smallest alignment change that passes the fixtures before considering a rewrite of the bounded alignment algorithm.

A unit may span multiple words when they are inseparable parts of one repair, such as `micaabans → mica abans`. Independent spelling errors on neighboring words remain separate. Formatting inside a number must preserve its digits; `2130 → 21:30` is one formatting fix when requested by the punctuation setting.

Retain Unicode/grapheme integrity, CRLF handling, sentence boundaries, actual link protection, original-position mapping, Accept/Undo ordering, and existing input/work limits. Link protection must validate the complete safe repair without mistaking a broken intermediate alignment for the intended text. Do not weaken it globally.

## 2. Simplify the language contract and make rejection honest

Correctness repairs errors; rewording offers a justified change of expression. Preserve valid colloquial wording, facts, speaker, tense, and numbers.

For the user's [obligation-and-verb example](../eval/fuzz/user_atomicity_case.json), the expected model correction is `tinc que usar → he d'usar`, preserving valid `usar`. The engine must reject the saved bundled rewording `tinc que usar → he de fer servir`; it must not invent an unreturned linguistic correction. Likewise, a valid `què tal` is not an invitation to substitute `com vas`.

One contiguous span, one sentence, one reported item, or a reason without “and” does not establish semantic atomicity. A multiword grammatical construction can be one decision; adjacent grammar and vocabulary changes can be two. Do not split a questionable phrase into fragments just to increase its count.

Enforce mechanical boundaries in core. Withhold suggestions whose placement or independence cannot be established. Semantic quality still requires reviewed language examples: neither regexes nor model self-report can guarantee it for arbitrary language.

Consolidate the shipped prompt's overlapping correctness, naturalness, and listing rules around that contract. Replace redundant or contradictory instructions and examples instead of appending another warning for each failure. Keep one shared prompt/schema across providers. Change the schema only if a concrete failed case requires information the current response cannot express.

[CheckResult.looksGood](../app/src/main/java/com/evanaronson/linguize/core/CheckResult.kt) currently infers success from an empty edit list. Remove that inference for answers whose substantive suggestions were discarded as unsafe or unusable. Carry the smallest necessary distinction through the existing result/card path: “no correction needed” and “could not provide a safe suggestion” must not become the same claim. Preserve the explicit wrong-language result.

## 3. Close input regressions and prove the replacement

Line-break correction belongs to correctness and the same edit pipeline. An accidental break between words becomes a space; one inside a word is removed. Preserve deliberate paragraphs, lists, and verse. Test LF and CRLF, repeated spaces, punctuation beside words, combining marks, and emoji. Do not add a global whitespace-flattening preprocessing path that loses original offsets.

Turn the [confirmed failures](../eval/fuzz/confirmed_engine_failures.json) into hard assertions on independently specified accepted text:

| Witness | Required outcome |
| --- | --- |
| `ca-035`: `micaabans` | One complete spacing repair; no standalone insertion that duplicates `mica` or replacement that loses it. |
| `sp-036`: `Revisarelos docum:entos` | Repair each complete unit without `docum → los` or borrowing neighboring text. |
| `sp-050`: `quévoy a hace\r\nr` | Repair the joined words and the split word independently; accepting either leaves the other words in place. |
| `ca-024`: `pasti:lla despres` | Separate `pasti:lla → pastilla` and `despres → després` decisions. |
| `sp-012`: `2130` | Formatting preserves digits, produces no rewording, and never yields `212130` or `30`. |
| `ca-005`: `alsofà.Si` | Restore the intended spacing without duplication; real URL introduction remains blocked. |

Include the remaining confirmed fixture, sentence-crossing examples, and the user's screenshots. Check each individual Accept, valid acceptance orders, Accept all, Undo, and rewording before/after overlapping fixes. An expected result must not be computed from the same edit being tested.

Add focused negative controls for named-language mismatch versus Auto Detect; already-natural colloquial wording; deliberate line breaks; real links; and rejected suggestions. Cover each punctuation and judgment mode at these boundaries, plus saved-answer reuse. Extend existing tests and eval cases rather than building another harness.

Replay all 200 frozen answers after each engine change. The audit collector can remain permissive to capture evidence, but known repair regressions must fail tests. Renew manual reviews when rendered actions change; old review hashes are not approval of new output.

After the engine gates pass, rerun the same live corpus with the shared checks. Use the default model and an available alternative for targeted regressions, recording the actual responder and failures. Retry failed requests only; never retry an unfavorable judgment until it looks good. Report timeouts against the app's 30-second limit. Language-quality review and deterministic engine assertions remain separate.

## Delivery and completion

Deliver three bounded changes in the order above, each with its own removal summary and tests. Do not leave old/new engines, temporary fallback branches, or duplicate rules running together. Explain any complexity that remains instead of introducing an arbitrary deletion quota.

Completion requires the known corrupting Accept actions to be gone, the semantic and input cases reviewed, all 200 replays examined for regressions, and unit tests, lint, and the build passing. Check source-app replacement on a handset because replay cannot establish Android writeback behavior. Record remaining linguistic limitations plainly; a green evidence-capture job is not a quality pass.
