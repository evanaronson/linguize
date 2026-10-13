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

Each damaged text is checked once in its named language and once in Auto Detect: 200 provider requests. The runner uses the shipped prompt/schema and existing provider evaluation adapter. It saves every answer or request failure, request settings, mutation trace and prompt/corpus hashes. Resume files must match the original provider, model, prompt and corpus. Credentials belong in environment/repository secrets; output never includes headers or credential values.

`FuzzReplayTest` feeds saved replies through the production Checker, parsing, interpretation, history reuse and edit engine. It records all offered edits and each individual Accept result, both previews and Accept all. It checks original span mappings, sentence boundaries, overlaps, previews, Undo, acceptance order and link safety. Ordinary app test runs skip this opt-in replay.

The live workflow runs only on explicit workflow dispatch or a change to `request.json` on `feature/fuzz-100`; it does not make billed calls on ordinary pushes or pull requests. Results are uploaded as `fuzz-100-evidence`.

Every provider and rendered result must also be reviewed for linguistic quality and independent phrasing. The original clean text is context, not a mandatory exact output: random word substitutions can change the meaning without making the new text incorrect. A valid alternative correction is not automatically a failure. Synthetic inputs contain no user history or API keys.

The corpus deliberately includes U+2028 line separators. Read JSONL records using LF delimiters (`split("\n")`) or file iteration; Python `splitlines()` would incorrectly split these characters inside a JSON string.

Transient 429/5xx failures are retried with bounded backoff and globally paced request starts. `--retry-errors` retries failed saved requests while retaining successful replies; the resume file remains one latest record per request. A provider failure is not counted as a checked passage.
