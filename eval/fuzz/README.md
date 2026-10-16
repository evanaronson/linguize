# Corruption exercise

Generate 100 distinct synthetic 2–3 sentence passages (50 Catalan, 50 Spanish), then apply seeded random mutations:

```sh
python3 eval/fuzz.py
python3 -m unittest discover -s eval -p 'test_fuzz.py'
GEMINI_API_KEY=... python3 eval/fuzz.py --live
LINGUIZE_FUZZ_REPLIES="$PWD/eval/fuzz/results/replies.jsonl" \
LINGUIZE_FUZZ_ENGINE="$PWD/eval/fuzz/results/engine.jsonl" \
./gradlew testDebugUnitTest --tests com.evanaronson.linguize.FuzzReplayTest
```

Each damaged text is checked once in its named language and once in Auto Detect: 200 provider requests. The corpus and checks are model agnostic. Select the provider with `--provider gemini|openai` and any supported model with `--model MODEL_ID`; use separate `--replies` paths for model comparisons. The runner uses the shipped prompt/schema and existing provider evaluation adapter. It saves every answer or request failure, request settings, mutation trace and prompt/corpus hashes. Resume files must match the originally requested primary provider/model, prompt and corpus. Mixed fallback answers retain their actual provider/model and requested primary target. Credentials belong in environment/repository secrets; output never includes headers or credential values.

`FuzzReplayTest` feeds saved replies through the production Checker, parsing, interpretation, history reuse and edit engine. It records all offered edits and each individual Accept result, both previews and Accept all. It checks original span mappings, sentence boundaries, overlaps, previews, Undo, acceptance order and link safety. Ordinary app test runs skip this opt-in replay.

Replay defaults to `LINGUIZE_FUZZ_PARSER=fresh`: initial and recorded repair answers use the shipping reader, including required numeric suggestion scores. Historical captures that predate scoring require the explicit `LINGUIZE_FUZZ_PARSER=legacy` option. The console and every engine row identify the selected parser mode. Live and unknown captures use fresh mode; compatibility is never inferred from an answer's fields.

Each new replay row includes its parsed `replay_input`. Reports require that input's case, actual provider/model, prompt/schema and corpus hashes, verdict and optional repair to match the current reply. Reordering JSON keys or changing top-level collection timing does not require another replay. Changing an answer or adding a repair does. Repair collection checks this binding before using withheld flags for requests without a recorded repair; a saved repair attempt is never repeated when resuming against the initial replay. Final reports still require a new replay of those repairs.

The workflow defaults to offline replay of committed evidence when dispatched, explicitly using the legacy parser for that pre-scoring baseline. Enable its `live` input explicitly to make billed calls, select a provider/model, and optionally supply ordered fallback targets. Pull requests replay committed evidence without provider requests. An optional `reuse_run` input retains successful answers from an earlier evidence run; live mode retries only its failures. Live and resumed runs use the fresh parser. Results are uploaded as `fuzz-100-evidence`.

Every provider and rendered result must also be reviewed for linguistic quality and independent phrasing. The original clean text is context, not a mandatory exact output: random word substitutions can change the meaning without making the new text incorrect. A valid alternative correction is not automatically a failure. Synthetic inputs contain no user history or API keys.

The corpus deliberately includes U+2028 line separators. Read JSONL records using LF delimiters (`split("\n")`) or file iteration; Python `splitlines()` would incorrectly split these characters inside a JSON string.

Transient 429/5xx failures are retried with bounded backoff and globally paced request starts. `--retry-errors` retries failed saved requests while retaining successful replies; the resume file remains one latest record per request. A provider failure is not counted as a checked passage.

Fallback targets use `--fallback PROVIDER:MODEL` (repeat for an ordered list). For example:

```sh
python3 eval/fuzz.py --live --fallback gemini:gemini-3.5-flash --retry-errors
python3 eval/fuzz.py --live --provider openai --model MODEL_ID --replies eval/fuzz/results/openai.jsonl
```

Only failed requests trigger fallback. A valid answer with bad suggestions stays in the audit; selecting a more favorable judgment would hide bugs. Credentials for every selected provider must be configured before any call. Failed attempts are preserved without error response bodies or credentials.

`--timeout` defaults to 30 seconds. The original diagnostic audit used 90 seconds; the [repair evaluation](REPAIR.md) uses 30 seconds. Records and reports distinguish answers beyond the app's 30-second call limit. Retries and fallback do not simulate the app's actual waiting behavior.

The replay continues after recording invariant violations. A successful replay enforces its deterministic safety gates and captures evidence; it does not establish linguistic quality. The diagnostic flags and manual review ledger expose both structural failures and linguistic concerns.

Frozen evidence and the completed findings are described in [REPORT.md](REPORT.md). The legacy `baseline/engine.jsonl` and its derived review artifacts are **unbound**: they predate replay-input provenance. They remain historical evidence and cannot pass the default report gate. Do not add inferred provenance to old outputs. To produce separate, newly bound evidence without API access:

```sh
mkdir -p eval/fuzz/results
cp eval/fuzz/baseline/replies.jsonl eval/fuzz/results/replies.jsonl
LINGUIZE_FUZZ_REPLIES="$PWD/eval/fuzz/results/replies.jsonl" \
LINGUIZE_FUZZ_ENGINE="$PWD/eval/fuzz/results/engine.jsonl" \
LINGUIZE_FUZZ_PARSER=legacy \
./gradlew testDebugUnitTest --rerun-tasks --tests com.evanaronson.linguize.FuzzReplayTest
python3 eval/fuzz_report.py --results eval/fuzz/results
```

This executes the current engine against frozen answers; it does not reproduce the historical engine output or transfer its manual reviews.

To inspect the historical pairing without replaying, copy its source files to a separate output directory and explicitly select `--legacy-unbound`:

```sh
mkdir -p eval/fuzz/results/historical
cp eval/fuzz/baseline/replies.jsonl eval/fuzz/baseline/engine.jsonl eval/fuzz/results/historical/
python3 eval/fuzz_report.py --results eval/fuzz/results/historical --legacy-unbound
```

This labels the report, summary and joined rows as `legacy_unbound`; it cannot verify that those outputs consumed the paired answers. It never overrides mismatched recorded provenance and cannot be combined with `--require-repairs` or used to select live repairs. This report-only option is separate from `LINGUIZE_FUZZ_PARSER=legacy`, which selects parsing compatibility when executing a new replay.
