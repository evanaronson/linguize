# Provider evaluations

`cases.jsonl` is a curated regression dataset. `run.py` obtains complete requests
from the app's Kotlin check preparation code, sends them to the selected provider,
and checks the expected fields in each case. The default `--mode app` includes the
same gaps and tone composition as ordinary app checks, plus reply context when a
case supplies it. `--mode base` explicitly selects the historical base prompt and
schema without those extensions. It rejects incomplete and refused provider replies before examining
the answer. It does not run Android's verdict validation, edit alignment, history
replay or replacement logic; those belong to `app/src/test/`.
Fresh adjustments require paired integer confidence and impact scores in 0–10;
the report prints their raw values on that scale.

```sh
# Offline: build/export actual Kotlin requests for review; no provider key needed.
python3 eval/run.py --prepare-only --output /tmp/prepared-checks.jsonl

# Live: pin that reviewed artifact and record answers and request identities.
GEMINI_API_KEY=... python3 eval/run.py --prepared /tmp/prepared-checks.jsonl --output /tmp/check-results.jsonl
OPENAI_API_KEY=... python3 eval/run.py --provider openai --model MODEL --output /tmp/openai-check-results.jsonl
python3 -m unittest discover -s eval -p 'test_*.py'
```

Preparation invokes `./gradlew :app:prepareCheckEval` once per dataset and requires
the same JDK/Android SDK setup as local app tests. It uses no provider credentials.
The Python bridge removes Gemini/OpenAI API keys from the Gradle child environment.
The task accepts `-PcheckEvalCases=ABSOLUTE_PATH`, `-PcheckEvalOutput=ABSOLUTE_PATH`
and `-PcheckEvalMode=app|base`. `--prepare-only` writes JSONL to stdout when no output
path is supplied. Python's offline unit tests mock the batch exporter; Kotlin tests
check frozen pre-refactor request bytes, hashes, provider envelopes and parser policy.

Live evaluations make billed network requests. CI runs only the offline tests:
the APK workflow covers pull requests and app builds, and a separate read-only
[eval workflow](../.github/workflows/eval.yml) covers eval changes pushed to `main`.
Keep API keys in the environment, never in datasets or checked-in files. Use
`--cases PATH` for a separate curated JSON Lines dataset. The runner exits `0` only
when every case passes, `1` for mismatches or provider errors, and nonzero for
invalid configuration. Invalid cases or failed Kotlin preparation abort the whole
batch before any provider call. It reports provider, model, preparation mode,
effective contract digests, dataset hash and latency. A failed provider request
or malformed reply is counted once; later cases still run and the summary includes
the failure. Latency statistics cover replies that could be evaluated.

`--output` creates a new file and refuses to overwrite existing evidence. Each live
result is flushed to disk before the next request, with its original case, exact
dataset digest, actual provider/model, prepared request, answer or error, and timing
when available. The CLI does not automatically make repair requests; the corruption
workflow below records and evaluates that separate step.

The version-1 prepared artifact records the original case and index, SHA-256 of
the exact dataset bytes, mode, and both initial and repair requests. Each request
contains the complete generation payload, unchanged app compatibility hash, base
parser policy/hash, an effective contract digest, and a request digest. Contract
digests cover mode, system text, schema, schema name, token limit and parser;
request digests additionally bind user text and the compatibility hash. They do
not replace historical app or fuzz hashes. `--prepared PATH` verifies the artifact's
digests, mode, order and exact dataset binding. It deliberately pins that exported
contract; it does not claim the artifact matches today's assets. Re-export to
evaluate current app code. Archived base evidence must remain labelled base.

Each case requires `text`. Optional request fields are `language` (`auto`, a language
code, or its English name), `punctuation`, `checks`, `native`, `writing_profiles`,
`replying_to`, `repair`, and `settled` pairs or scoped objects. Profiles use the
app's six fields (`id`, `name`, `language`, `variety`, `register`, `punctuation`);
preparation takes the first profile per language and sorts languages as the app's
settings snapshot does. Omitting profiles means an empty profile list; it does not
inject a fresh installation's default profiles or read a device's settings. Include
the complete profile snapshot to evaluate those choices. A non-null `replying_to` is passed exactly to preparation,
without applying Chat's UI trimming/length policy; base mode rejects it. Optional
expectations are `status`, `answer_language`, `fix`, `natural`, `max_fixes`,
`min_sentences`, `assumption`, `expected_corrected`, `expected_natural`,
`expected_fixes` and `expected_natural_changes`. An omitted expectation is not checked. The sentence
count is only a punctuation heuristic, not the app's sentence-boundary algorithm.
Negative examples (`natural: false`) help catch unnecessary rewriting.

Kotlin owns prompt composition, request formatting, profile serialization and
compatibility hashing for this runner. Python's old `user_message` helper remains
only for explicitly historical base fuzz evidence. Provider HTTP adapters are still
separate Python implementations, so update them alongside
changes to `llm/` and validate live results before drawing quality conclusions.
Shared preparation guarantees the logical `GenerationRequest` fields and recorded
identities. It does not unify Python's HTTP serialization, retry state, answer
parsing or the app's edit engine. Kotlin's frozen provider tests guard the app's
wire bytes independently.
Gemini retries once without a thinking option when the model rejects it, matching
Android's compatibility behavior. OpenAI has no corresponding option retry.

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
HTML/JSON reports. Its rubric, protocol and recorded results retain that scale
independently of the production checker's 0–10 scores.
