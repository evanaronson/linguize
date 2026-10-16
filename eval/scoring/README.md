# Display-only suggestion assessment experiment

This is retrospective scoring, not a new app feature. It does not change Android,
the production prompt, suggestion generation, filtering, ordering, acceptance,
Copy, History storage or replay. Both confidence and impact use ordinal 0..3
ratings, with required numeric answers for a successful assessment. The rubric is
in [rubric.md](rubric.md); the API contract is in [answer_schema.json](answer_schema.json).
Version and exact prompt/schema hash travel with each result. Zero is an assessed
outcome; null means assessment failed or has not happened.

## Start without credentials

From the repository root:

```sh
python3 -m unittest discover -s eval -p 'test_*.py'
python3 eval/score_suggestions.py report \
  --cases eval/scoring/cases.jsonl --out eval/private/starter-review
```

`cases.jsonl` contains 12 public synthetic contrast cases, some adapted from the
repository's existing examples. They deliberately include correct alternatives,
wrong tense/number changes and an explicit settled-meaning correction. They are
NOT past phone checks, independently rated references, a representative sample,
or a held-out validation set. Keep later held-out examples outside prompt tuning.

The report contains:
- `blind.html`: message, available context and exact adjustment, without scores,
  original model reasons, source-model identity, acceptance decisions or gold labels.
- `review.html`: the same table with each completed assessment and evidence.
- `references.csv`: blank reviewer judgments, keyed to case IDs and input hashes.
- `summary.json`: coverage, repeat consistency and reference comparisons when available.

Files are created exclusively. Choose a new output file/directory for each run.
Raw private data and reports belong in ignored `eval/private/` or outside the repo.
Do not commit messages, History exports, actual assessments or review judgments.

## Import a phone History export

```sh
python3 eval/score_suggestions.py prepare \
  --history /path/to/history.jsonl --out eval/private/history-cases.jsonl
```

The importer reads the app's JSONL session/suggestions export. It preserves the
exact historical `fromText`/`toText` and maps Android UTF-16 spans to Python code
point offsets, checking that the source span matches. It never regenerates changes.
The appropriate attempt supplies context, including only settled answers available
at that time. Current-schema attempts do not inherit the session's later settings;
older schema versions 1–3 can use their session-wide context. Missing context stays
unknown. Unknown kinds, bad offsets, nonexistent attempts, duplicate identities
and source mismatches fail rather than being silently guessed or skipped.

Original reasons and source provenance remain in the private imported case file
for audit, but the assessor receives only original text, exact span/replacement,
kind and available context. Acceptance decisions, later messages/answers, original
model reasons and reference ratings are excluded. Checks without adjustments yield
no scoring cases: this experiment cannot measure missed errors or generation recall.

## Score with a configured provider

Use an environment-configured `GEMINI_API_KEY` or `OPENAI_API_KEY`; do not put keys
in files or pass them as arguments. Explicit invocation below makes billed requests
and sends each original message and its adjustment/context to that provider.
The default is three separate calls per case (28 cases means 84 requests).
The provider adapters/default models come from `eval/run.py`; the scoring prompt
and schema are separate from production. No training or model reconfiguration occurs.

```sh
python3 eval/score_suggestions.py score \
  --cases eval/private/history-cases.jsonl --provider gemini \
  --repeats 3 --out eval/private/gemini-run-1.jsonl
python3 eval/score_suggestions.py report \
  --cases eval/private/history-cases.jsonl \
  --results eval/private/gemini-run-1.jsonl --out eval/private/gemini-review-1
```

The input files remain untouched. Each result retains the case/dataset hash,
provider/model, rubric version/hash, time, repetition, retrospective origin and
parsed raw answer when available. Failures remain null and make the command exit
nonzero, but later cases still run. CLI progress shows IDs rather than messages.
The response parser rejects nulls, booleans, fractional/out-of-range scores,
missing/extra fields and blank/overlong evidence. It never substitutes zeros.

## Independent review and comparison

Give a reviewer `blind.html` and `references.csv` before they see model scores.
Each row can record a reviewer identity, `justified` (`yes`, `no`, `uncertain`),
confidence/impact 0..3 and a reason. Blank means not assessed. Zero must be written
explicitly. Keep different reviewers' CSVs separately to retain disagreements.
An assistant-session pilot assessment is a model judgment, NOT independent truth.

```sh
python3 eval/score_suggestions.py report \
  --cases eval/private/history-cases.jsonl --results eval/private/gemini-run-1.jsonl \
  --references eval/private/reviewer-1.csv --out eval/private/compared-1
```

Comparisons enforce matching input hashes and one assessor/rubric per report.
They show confidence-bin justified counts/fractions and 4x4 reference-versus-model
confusion matrices. Repeated calls count as one reviewed case using the lower
median category; they are not independent evidence. Stability is exact agreement
on both numbers among cases with at least two successful assessments. Failures and
coverage are reported separately; incomplete runs can bias observed stability.

Without reviewed labels, fractions are null and there is no accuracy claim. These
ordinal ratings are not calibrated probabilities, and their product/average is
not a meaningful combined confidence/impact score. A small convenience sample
cannot establish global thresholds, predictive accuracy or an optimal scale.

First inspect adjacent-level disagreements, high-confidence unjustified changes,
repeat instability and differences by kind/language. Compare providers in separate
runs on the same cases. Review score-0 proposals as well as strongly supported
ones. Only later compare changed generation prompts against a frozen baseline and
held-out messages: retrospective scoring alone cannot reveal missed suggestions.
