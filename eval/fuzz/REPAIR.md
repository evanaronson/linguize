# Checker repair validation

Implementation: [PR #19](https://github.com/evanaronson/linguize/pull/19). The [repair contract](../../docs/checker-repair-plan.md) explains the intended behavior and removals. The final engine is `6fe7ce8c3acb6f4ccd9088fd99c71956014872ac`; subsequent delivery changes only adjust tests, documentation and workflow cleanup.

## Evidence

The seeded corpus contains 100 different passages of two or three sentences: 50 Catalan and 50 Spanish. Each corrupted passage is checked in its named language and Auto Detect, giving 200 requests. Mutations cover letters, accents, word substitutions, spaces, punctuation, and line breaks.

The original committed replies remain the frozen engine baseline. A separate live run evaluates the shorter shared prompt. Successful replies are retained, including bad judgments; only failed provider requests are retried. All 200 live replies have now been received and manually inspected. Each final engine replay must also be reviewed: a successful full-text preview does not establish that individual Accept actions are sound.

Live collection completed in [run 38585148127](https://github.com/evanaronson/linguize/actions/runs/38585148127), resuming runs [38508744021](https://github.com/evanaronson/linguize/actions/runs/38508744021) and [38583211822](https://github.com/evanaronson/linguize/actions/runs/38583211822). The evidence artifact includes the generated inputs, raw replies, provider failures, actual responders and engine results. Its engine snapshot predates the final repair; use the final replay for acceptance behavior.

| Responder | Successful replies |
| --- | ---: |
| Gemini 3.5 Flash Lite | 81 |
| Gemini 3.5 Flash | 3 |
| Gemini 3.1 Flash Lite | 116 |

Successful request latency was 2.00 seconds at the median and 22.16 seconds at the maximum; none exceeded the app's 30-second timeout. The evaluation HTTP adapter uses a 30-second socket timeout, while the Android client enforces its overall request deadline. The comparison concerns observed wall time, not identical timeout implementations.

Corpus SHA-256: `b0b464aa36cbd4458bd1caf8ca84a88cc9ac4c4e6ab95ef65ebbac786d6de075`.

Prompt SHA-256: `e0c5da1106d9554b704ff08c8afb2ed228af8ed135b1d4486fb9644bab8079c4`.

## Final replay review

Both 200-result replays passed their structural checks: [frozen replies](https://github.com/evanaronson/linguize/actions/runs/38634595315) and [fresh replies](https://github.com/evanaronson/linguize/actions/runs/38634588356). Independent Catalan and Spanish reviewers covered every result and individual Accept output. Unchanged reviews were inherited only after exact reply/engine hash checks; changed actions were inspected again. The per-request [frozen review](repair-frozen-review.csv) and [fresh review](repair-live-review.csv) preserve those hashes and findings. Hashes use UTF-8 JSON with sorted keys, compact separators and unescaped Unicode.

| Replay | Reviewed requests | Offered actions | Results with withheld proposals |
| --- | ---: | ---: | ---: |
| Frozen | 200 | 582 | 61 |
| Fresh | 200 | 589 | 54 |

No confirmed engine-created corruption or bundled independent Accept action remains in these replays. This is evidence for the reviewed corpus, not a semantic guarantee. Coverage is deliberately incomplete: some ordinary spelling fixes still sit inside ambiguous runs and are withheld. The review records those losses, including Spanish `r:ten → tren` and `felvicidades → felicidades`, separately from bad model judgments. The existing `sp-008` comma proposal leaves another pre-existing sentence error; it remains a policy candidate rather than confirmed new corruption.

The last review caught `Deqmà → demà` offered independently of a period-to-semicolon change. The engine now withholds that dependent pair while preserving independent repairs. Regression tests also cover a deleted word that leaves whitespace and a grammatical construction that must not reintroduce a withheld word.

## What the review found

Manual review caught failures that the structural replay gates did not: bundled spelling and deletion, capitalization combined with article insertion, split pronoun fragments, and word swaps that produced duplicates after one Accept. An attempted use of exact model-report boundaries introduced further broken joins. That approach was removed; exact report placement alone does not establish word correspondence or independence.

The engine must withhold unsupported transformations instead of guessing how to split them. Safe repairs elsewhere remain available, and a withheld proposal cannot produce a clean-result message. Regression cases preserve the actual incomplete or contradictory reports that triggered these bugs.

## Remaining language-quality limits

Successful evidence capture is not a passing language-quality score. The live answers include wrong language identification, invented words and facts, unnecessary preferences, incorrect explanations, and required grammar changes classified as rewordings. Examples include Catalan classified as French, changing a reference to yesterday into a delivery person, and replacing an ordinary word with a synonym merely by preference. The engine can faithfully place a bad one-word judgment; a diff cannot prove that judgment correct.

The focused runs returned 35 answers covering all 33 controls across default and alternative Gemini models. Eight answers missed exact assertions: seven substantive failures and one CRLF-to-LF normalization that preserved the paragraph layout. Substantive failures included ignored LF/CRLF wraps, merging two sentences, flattening a deliberate paragraph, fabricating a spelling, and treating grammar or an unrelated accent as naturalness. Returned wrong-language controls, valid “què tal”/“usar”, and the obligation example passed. Engine boundary checks separately reject unsupported sentence-merging proposals.

Provider failures are distinguished from returned judgments. Failed calls were retried; bad completed judgments were retained. No word blacklist or extra judging service is added to conceal them.

The test contract is shared across providers and models. Runtime provider selection and fallback behavior are unchanged. Android accessibility writeback still requires a handset check; these replays exercise text construction and acceptance, not another app's editable field.
