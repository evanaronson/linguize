# Checker repair

Implementation: [PR #19](https://github.com/evanaronson/linguize/pull/19), based on `2489aa2`.
The [repair evaluation](../eval/fuzz/REPAIR.md) records the completed corpus review and remaining limits. The [original audit](../eval/fuzz/REPORT.md) reviewed 100 corrupted passages in their named language and Auto Detect: 200 requests, with repeated engine findings in 77 requests.

## Contract

Each Accept action must describe one independently meaningful repair. Accept all producing plausible text does not establish that individual actions are correct. One reported item, one sentence, or a reason without “and” does not prove semantic atomicity.

Correctness repairs errors while preserving valid wording. Rewording offers a clearly useful change of expression within one existing sentence and paragraph. In the user's example, `tinc que usar → he d'usar` repairs obligation while preserving `usar`; changing the verb to `fer servir` adds an independent preference. Valid `què tal` stays valid.

The owner confirmed independent decisions, one-sentence rewordings, preservation of valid wording, usable line-break repairs, and honest language results. Dependent punctuation/capitalization grouping and runtime retry policy remain open product choices.

Core validates exact placement, lexical boundaries, sentence scope, link policy and edit conflicts. Word counts and report shape are admission heuristics, not proof of one linguistic decision. The digit-sequence guard applies to rewordings, not correctness repairs. The prompt instructs the model about meaning; reviewed cases measure compliance. Neither provides a guarantee for arbitrary input.

## Responsibility and justification

| Changed mechanism | Functional objective | Architectural principle / disposition |
|---|---|---|
| Grapheme alignment, word anchors, lexical projection, separator ownership | Complete editable words, Unicode and spacing | Pure core owns coordinates; retain mechanical representation |
| Shared alignment work budget | Bound processing of model output | One request-wide resource limit; retain |
| Correction runs, grammar/boundary pairs, corroboration and salvage | Infer independent corrections | Unconfirmed admission policy; redesign candidate, not a semantic guarantee |
| Exact report location and unchanged-context trimming | Accept precisely the disclosed change | One source of ranges; retain exact validation |
| Natural word-count/anchor gate | Reject bundled rewordings | Approximation with false accepts and false rejects; not a product invariant |
| Original-coordinate mapping, included fixes and conflicts | Accept in either order, then undo | Core owns composition; retain pending dependent-change decision |
| Withheld state and language contradiction check | Never equate missing suggestions with clean text | CheckResult owns interpretation; retain |
| Deletion/spacing controls and context | Make invisible changes actionable | UI owns presentation; context must not enlarge domain edits |
| Empty historical rows and neutral summaries | Do not invent past suggestions | History owns recorded evidence; current revalidation is not historical truth |
| Shared prompt and corpus/replay tools | Evaluate linguistic judgments across models | One shipped prompt; separate mechanical validity, linguistic quality and coverage |

The independent architecture review maps each changed production function to these responsibilities. No new service, module, judge model or storage schema is justified by this repair. Any redesign must compare retained valid repairs as well as prevented bad actions.

## Replacement and removal

The implementation has one path for each kind of edit:

1. Preserve uniquely shared whole words, align graphemes within the remaining runs, and project corrections onto complete lexical and whitespace units in both texts. A mechanical split or joined word remains one repair. Withhold opaque multiword runs unless exact reports corroborate the already-derived independent repairs or an existing grammatical construction. Reports never cut alignment or force word correspondence.
2. Place reported rewordings against both complete texts. Reject ambiguous placement, sentence crossing, number changes, independently changed regions, and opaque substitutions involving multiple words on both sides. Map accepted changes back to the original once, including the corrections they depend on.
3. Publish actions that pass the structural checks and retain a small per-judgment incomplete state when proposals were withheld. An empty list caused by rejection must not say “Looks good.”

This replaces `group`/`spans` cleanup, `bridgeWords` and its phrase vocabulary, `mergeWithinFixes`, fallback rewording diffs, fuzzy reason matching, duplicate-deletion ownership guesses, and greeting-specific filtering. Superseded alignment spelling/elision/tidying rules are removed. The prompt consolidates correctness, naturalness and change-list rules instead of appending another exception for every screenshot.

A run that mixes a word insertion or deletion with changed punctuation cannot expose the resulting fragments as separate Accept actions.

Current grouping policies allow certain reported grammatical constructions to own adjacent correction units, and punctuation/case repairs to own a sentence boundary. These policies have regression fixtures but lack separate owner confirmation. Missing or malformed reports withhold those coupled fragments. Display context never expands the actual replacement: deletion and whitespace actions get visible controls in the existing card.

## Deliberate limits

Withholding is lost coverage as well as protection. The completed audit found withheld proposals in 61/200 frozen and 54/200 fresh results, including valid repairs. Green replay is not a language-quality pass. These counts precede the fresh adversarial-review fixes and must be renewed when offered actions change.

- Unsupported multiword runs, including word relocations, are withheld before exposing their fragments. This also withholds some valid adjacent spelling repairs when reports are absent or incomplete. Word counts alone cannot distinguish independent typos from a fragmented joined word. Some structurally supported changes elsewhere, a sole complete word substitution, and case-preserving accent or spacing repairs remain available. Exact local source/target matches can corroborate already-derived independent word repairs; case changes that depend on a withheld boundary remain withheld.

- Rewordings with changes on both sides of an unchanged lexical anchor are withheld. This blocks the audited pronoun deletion plus tense change. It also withholds some valid discontinuous constructions; an equivalent local contraction can still be offered. A pure insertion touching corrected wording, or a rewording touching a deleted word, is withheld when its dependency cannot be established; unchanged report context never pulls unrelated fixes into a rewording.
- A malformed boundary such as `alsofà.Si` can resemble a bare domain. The existing link policy remains conservative. The independently safe boundary-space action remains available; an ambiguous domain-changing word repair may need another check after that space is accepted. No unsafe combination is offered merely to reproduce the model's complete result.
- Unchanged spelling, facts, speaker and tense still require language-quality review. Structural validation is not a second language model.

## Validation and delivery

The [repair evaluation record](../eval/fuzz/REPAIR.md) keeps evidence, failures and model-quality limits. Use the existing tests and evaluation scripts, with the same contract for every model. Frozen provider answers isolate engine regressions; live answers evaluate the changed prompt. Keep actual responders, failures and the app's 30-second timeout in the evidence. Retry failed requests only.

Hard regressions cover the seven confirmed audit fixtures, the user's bundled rewording, whole-word anchoring, punctuation/space replacement, numeric formatting, reported grammar, individual Accept, different acceptance orders, Accept all and Undo. Input controls cover LF/CRLF, deliberate paragraphs and lists, combining marks, emoji, wrong language versus Auto Detect, and saved results.

Renew the 200-result manual review whenever offered actions change. Complete the live corpus and focused default/alternative-model controls; record remaining language failures without treating successful evidence capture as a quality pass. Unit tests, lint and the APK build must pass before merge. Handset writeback remains a device check; replay cannot establish Android accessibility behavior.

No runtime provider fallback, new edit framework, extra judging service or history schema is introduced. Temporary evaluation triggers are removed before merge.
