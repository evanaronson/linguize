# Checker repair

Implementation: [PR #19](https://github.com/evanaronson/linguize/pull/19), based on `2489aa2`.
The [evaluation record](../eval/fuzz/REPAIR.md) holds corpus results, model failures and the revision each review covers. The [original audit](../eval/fuzz/REPORT.md) checked 100 corrupted passages in their named language and Auto Detect: 200 requests.

## Functional contract

Each Accept represents one independently meaningful decision. A grammatical repair may require several words or dependent changes. The owner permits necessary dependencies, including a sentence boundary with its required capital and a spelling correction inside a reworded phrase. Independent preferences remain separate: `tinc que usar → he d'usar` repairs obligation; replacing `usar` with `fer servir` adds a different choice. Valid `què tal` stays valid.

Correctness repairs errors while preserving valid wording and intended meaning. Rewording offers a useful expression change within one existing sentence and paragraph. Accidental line breaks must be repairable, including breaks inside words. Missing or rejected suggestions must not produce a clean-result claim. An incomplete answer gets one retry before the app returns the available result.

One report, one sentence, or one short explanation does not prove semantic independence. The model makes linguistic judgments; core checks whether its proposed changes can be represented and accepted consistently. Neither role guarantees correct meaning or atomicity for arbitrary language.

## Requirement to mechanism

| Requirement | Mechanism and owner |
|---|---|
| Complete words and exact replacements, including Unicode and whitespace | `Alignment` aligns graphemes with shared word anchors and a bounded work budget. `Edits` projects mechanical repairs onto complete lexical and whitespace units in both texts. Core owns coordinates. |
| One disclosed linguistic decision, including multiword grammar | `Edits.located` requires unambiguous reports that own complete projected components in both texts and reproduce the full target. Wording changes keep their reported scope. Orthographic/formatting changes can be split mechanically, with dependent boundary/case changes grouped. The prompt supplies the linguistic contract; exact placement does not prove it. |
| Usable handling of malformed correction reports | With no consistent report list, mechanically equivalent output can still yield complete repairs. For wording changes, only complete case-preserving accent/word-spacing repairs are salvaged; incomplete reconstruction sets the withheld state. |
| Rewordings stay local and do not hide separate formatting repairs | Natural changes require exact reports, sentence scope, unchanged digit sequence and sentence stops, whole-word boundaries, and no separate orthographic change around unchanged word anchors. Multiple changed words alone do not cause rejection. |
| Accept in either order and undo | Rewordings map from retained corrections back to original coordinates once, include necessary overlapping fixes, and reject conflicting mappings. `Revision` owns acceptance, retirement and undo. |
| Repair invisible changes without enlarging their scope | The shared card gives deletions and whitespace their own visible controls. Presentation context never expands a replacement. |
| Honest language and completion status | `CheckResult` checks contradictory detected/selected language and tracks withheld judgments. The card distinguishes unavailable proposals from no changes needed. Link checks prevent newly introduced links, including combinations of accepted edits. |
| One recovery attempt for incomplete results | `Checker` retries once with the same input, provider, model and settings plus the shared repair instruction. It selects a whole answer, never splices suggestions or ranks language quality by edit count. |
| Preserve recorded history evidence | Replay retains recorded suggestion rows and decisions; an empty recorded set cannot acquire new suggestions. Neutral summaries avoid claiming that zero recorded changes means good text. Current interpretation still affects historical status; it is not a snapshot of every original display detail. |

The retry replaces the first answer only when its withheld judgments form a proper subset of the first answer's. Retry failure or a non-improving result preserves the first answer; cancellation propagates. Stored-answer reuse does not retry. No automatic provider fallback or history schema change is introduced.

## Removed complexity

The final correction path removes the correction-run classifier, dependent-join detection, grammar-pair admission rules and report-corroboration heuristics. Rewording no longer treats a many-to-many substitution or several lexical regions as proof of multiple decisions. Reports supply linguistic scope; alignment supplies mechanical boundaries.

The PR also replaces the earlier `group`/`spans` cleanup, `bridgeWords` vocabulary, `mergeWithinFixes`, fallback rewording diffs, fuzzy reason matching, duplicate-deletion ownership guesses and greeting-specific filtering. There is no new module, edit framework or judging service. The shared shipped prompt remains the source for both app and evaluation instructions.

## Limits and validation

The digit-sequence guard applies to rewordings, not correctness repairs. It cannot prove numeric meaning or protect quantities written as words. Sentence detection is heuristic. Paragraph preservation and linguistic distinctions such as valid idiom versus unnecessary preference still depend on the model. A coherent report can bundle independent decisions or change meaning; structural acceptance does not establish linguistic correctness.

Withholding also loses useful coverage. Evaluate bad offered changes, missed valid repairs and incomplete results separately. Existing corpus failures remain evidence of limitations; changing the engine or prompt does not resolve them by itself. Frozen answers isolate engine behavior, while fresh answers assess the prompt/model combination. Neither a green replay nor a finite manually reviewed corpus establishes a semantic guarantee.

Regression checks cover exact report placement, complete words, multiword grammar, boundary/case dependencies, LF/CRLF, paragraphs, combining marks, emoji, spacing/deletions, individual Accept, acceptance order, Accept all, Undo, wrong language, incomplete results, bounded retry and history. Renew affected corpus review when offered actions change, retaining actual responders, failures and timeout evidence in the evaluation record.

Unit tests, lint and the APK build must pass before delivery. Handset writeback remains a device check: replay cannot establish Android accessibility behavior. Keep remaining linguistic failures visible in the evaluation record rather than treating successful evidence capture as a quality pass.
