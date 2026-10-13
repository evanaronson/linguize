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

The workflow defaults to offline replay of committed evidence when dispatched. Enable its `live` input explicitly to make billed calls, select a provider/model, and optionally supply ordered fallback targets. A change to `request.json` on `feature/fuzz-100` selects its recorded mode; ordinary pushes and pull requests make no provider requests. Results are uploaded as `fuzz-100-evidence`.

Every provider and rendered result must also be reviewed for linguistic quality and independent phrasing. The original clean text is context, not a mandatory exact output: random word substitutions can change the meaning without making the new text incorrect. A valid alternative correction is not automatically a failure. Synthetic inputs contain no user history or API keys.

The corpus deliberately includes U+2028 line separators. Read JSONL records using LF delimiters (`split("\n")`) or file iteration; Python `splitlines()` would incorrectly split these characters inside a JSON string.

Transient 429/5xx failures are retried with bounded backoff and globally paced request starts. `--retry-errors` retries failed saved requests while retaining successful replies; the resume file remains one latest record per request. A provider failure is not counted as a checked passage.

Fallback targets use `--fallback PROVIDER:MODEL` (repeat for an ordered list). For example:

```sh
python3 eval/fuzz.py --live --fallback gemini:gemini-3.5-flash --retry-errors
python3 eval/fuzz.py --live --provider openai --model MODEL_ID --replies eval/fuzz/results/openai.jsonl
```

Only failed requests trigger fallback. A valid answer with bad suggestions stays in the audit; selecting a more favorable judgment would hide bugs. Credentials for every selected provider must be configured before any call. Failed attempts are preserved without error response bodies or credentials.

`--timeout` defaults to 30 seconds. The live audit uses 90 seconds to collect diagnostic answers; records and reports distinguish answers beyond the app's 30-second call limit. Retries and fallback do not simulate the app's actual waiting behavior.

The replay continues after recording invariant violations. Its successful completion means all evidence was captured, not that suggestions passed. The diagnostic flags and manual review ledger expose both structural failures and linguistic concerns.

Frozen evidence and the completed findings are described in [REPORT.md](REPORT.md). To review or replay it without API access:

```sh
python3 eval/fuzz_report.py --results eval/fuzz/baseline
LINGUIZE_FUZZ_REPLIES="$PWD/eval/fuzz/baseline/replies.jsonl" \
LINGUIZE_FUZZ_ENGINE="$PWD/eval/fuzz/results/engine.jsonl" \
./gradlew testDebugUnitTest --tests com.evanaronson.linguize.FuzzReplayTest
```
