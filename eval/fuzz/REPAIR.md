# Checker repair validation

Implementation: [PR #19](https://github.com/evanaronson/linguize/pull/19). The [functional contract](../../docs/checker-repair-plan.md) maps requirements to code and documents removed heuristics. This record distinguishes mechanical engine guarantees from linguistic model quality.

## Corpus and method

The deterministic corpus contains 100 different passages of two or three sentences: 50 Catalan and 50 Spanish. Every corrupted passage is checked in its named language and Auto Detect, giving 200 requests. Mutations cover letters, accents, word substitutions, spaces, punctuation and line breaks. Corpus SHA-256: `b0b464aa36cbd4458bd1caf8ca84a88cc9ac4c4e6ab95ef65ebbac786d6de075`.

Frozen replies isolate changes to the engine. Fresh replies test the current shared prompt; successful answers, including bad judgments, are retained. Provider fallback applies only to failed calls. Incomplete results receive one recorded same-model repair attempt, matching the app. Retry failures preserve the first partial result, and resumed evaluation never repeats a recorded repair attempt.

Every result and individual Accept output is reviewed against the corrupted source. A complete corrected preview is insufficient: Accepting just one suggestion, accepting in different orders, and Undo must remain coherent. Unchanged reviews are reused only after exact evidence comparison. The per-request CSVs bind reviews to the actual initial verdict, optional repair response and engine output using UTF-8 JSON with sorted keys, compact separators and unescaped Unicode.

## Current evidence

Final production engine: `0710094282fb56611d6d569d36a09aceefd8fcda`. Later commits contain only evidence updates and removal of the temporary branch audit trigger. Final replay runs: [frozen](https://github.com/evanaronson/linguize/actions/runs/38662336369) and [live with recorded retries](https://github.com/evanaronson/linguize/actions/runs/38662332326). Live collection began in [38660138912](https://github.com/evanaronson/linguize/actions/runs/38660138912); resumptions preserve successful answers and existing repair attempts.

| Replay | Reviewed requests | Offered actions | Results with withheld proposals |
| --- | ---: | ---: | ---: |
| Frozen | 200 | 459 | 75 |
| Fresh | 200 | 477 | 56 |

The live audit recorded 68 repair attempts, including 7 failures. Twelve retry answers improved the set of withheld judgments and were selected. The newly rejected cross-period bundle was recovered as three independent repairs by its single retry.

The [frozen review](repair-frozen-review.csv) and [fresh review](repair-live-review.csv) cover all 200 requests each. Actual fresh responders were Gemini 3.5 Flash Lite (186), Gemini 3.5 Flash (7), and Gemini 3.1 Flash Lite (7). The final evidence preserves each responder and failed attempt. First successful-response latency was 1.05 seconds at the median and 12.41 seconds at the maximum; failed attempts and the extra repair request add time separately.

Fresh prompt SHA-256: `f04713e53de732bb3a7f81f254f1c7d9193c699f1a99f39797e2e4805f1a3a87`. The older frozen responses use a previous prompt and contain no real repair attempts; their repeated second answer is instrumentation, not evidence that a model retry improved anything.

## Findings acted on

Independent reviews found that word-count, correction-run and grammar-pair classifiers withheld valid multiword grammar without proving independence. They were removed. The engine now checks exact reports, complete source/target components, sentence scope and application consistency; the model supplies linguistic decisions.

Review caught two partial-accept failures: splitting `!. → !` could produce `!!`, and splitting `petit;a` could leave an extra `a`. Regression tests now require complete component ownership. Ownership excludes outer separator context so valid grammar, duplicate-word deletion and CRLF insertions remain usable. Sentence-stop grouping excludes adjacent quotes and emoji. A fresh answer also bundled two word repairs across an unchanged period; the retained-boundary scope check rejects that report while keeping complete independent repairs.

Multiword grammar and necessary dependencies remain permitted. Tests preserve `see → to seeing`, obligation constructions, boundary punctuation with its required capital, exact Unicode positions, accepting suggestions in either order, Undo, one bounded retry, and honest incomplete/history status.

## Remaining limitations

A green build or replay is not a passing language-quality score. The fresh model answers still contain semantic errors: changes to person or time, invented Catalan words, an invalid Spanish correction, and optional rewrites with dependent agreement in a later sentence. In particular, `sp-038` offers a plural-subject rewrite and a later singular-to-plural verb change as separate optional actions. Neither change crosses a textual sentence boundary, so the structural gate cannot establish their semantic dependency. These findings remain in the per-request review instead of being hidden by vocabulary blacklists or a claim that token geometry proves meaning.

Coverage is also incomplete. Some malformed report lists cause useful spelling repairs to be withheld, and some model answers leave accidental line breaks without proposing a fix. One retry helps some cases but does not solve every omission. The interface can report discarded proposals; it cannot identify a mistake the model never reports.

Unit tests, lint and APK compilation validate the implementation. Android accessibility writeback still requires a handset check; these replays validate text construction and acceptance, not another app's editable field. Earlier review results at `6fe7ce8` remain historical evidence in Git history and are superseded by the current CSVs.
