# Provider evaluations

`cases.jsonl` is a curated regression dataset. `run.py` sends the prompt and schema
from `app/src/main/assets/` to the selected provider and checks the expected fields
in each case. It rejects incomplete and refused provider replies before examining
the answer. It does not run Android's verdict validation, edit alignment, history
replay or replacement logic; those belong to `app/src/test/`.

```sh
GEMINI_API_KEY=... python3 eval/run.py
OPENAI_API_KEY=... python3 eval/run.py --provider openai --model MODEL
python3 -m unittest discover -s eval -p 'test_*.py'
```

Live evaluations make billed network requests. CI runs only the offline tests:
the APK workflow covers pull requests and app builds, and a separate read-only
[eval workflow](../.github/workflows/eval.yml) covers eval changes pushed to `main`.
Keep API keys in the environment, never in datasets or checked-in files. Use
`--cases PATH` for a separate curated JSON Lines dataset. The runner exits `0` only
when every case passes, `1` for mismatches or provider errors, and nonzero for
invalid configuration. It reports provider, model, prompt/schema hash, dataset hash
and latency so results can be compared against a known baseline. A failed request
or malformed reply is counted once; later cases still run and the summary includes
the failure. Latency statistics cover replies that could be evaluated.

Each case requires `text`. Optional request fields are `language` (`auto`, `Catalan`
or `Spanish`), `punctuation`, `checks`, `native` and `settled` pairs. Optional
expectations are `status`, `answer_language`, `fix`, `natural`, `max_fixes`,
`min_sentences` and `assumption`. An omitted expectation is not checked. The sentence
count is only a punctuation heuristic, not the app's sentence-boundary algorithm.
Negative examples (`natural: false`) help catch unnecessary rewriting.

Request formatting mirrors `Prompt.userMessage`, including language varieties and
normalization of settled answers; offline tests guard known drift points. Provider
HTTP adapters are still separate Python implementations, so update them alongside
changes to `llm/` and validate live results before drawing quality conclusions.
They use the initial request options, without Android's retry that removes an
unsupported reasoning/thinking option.

The live corruption workflow first replays the collected answers, then asks the same
actual model once to repair structurally incomplete results (`Repair: true`). It
retains both answers and replays the production checker to select the result. The
final report requires recorded retry evidence; frozen replay alone cannot measure
whether a model improves its answer.

For future learning, start from a reproducible failure, inspect the stored evidence,
then write a small reviewed example with explicit expectations. Do not automatically
copy private messages, raw exports or uncertain model suggestions into this repo.
Keep a held-out set separate from examples used to tune a prompt or learner, record
the baseline, and compare fixes, unwanted rewrites and latency by language. Passing
this dataset is evidence about its cases, not proof that a learner improves every
writer. The learning and evidence design is in [architecture](../docs/architecture.md).

## Retrospective confidence and impact experiment

[Suggestion assessment](scoring/README.md) evaluates exact existing adjustments
outside the app using separate 0–3 confidence and impact ratings. It supports
phone History exports, repeated provider calls, blind reference review and local
HTML/JSON reports. Production checking behavior remains unchanged.
