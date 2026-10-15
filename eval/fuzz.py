#!/usr/bin/env python3
"""Seeded multilingual corruption corpus and resumable live provider checks."""
import argparse
import collections
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import sys
import time
import threading
import unicodedata
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SEEDS = Path(__file__).with_name("fuzz") / "seeds.json"
WORDS = re.compile(r"[^\W\d_]+(?:['’·-][^\W\d_]+)*", re.UNICODE)
OPERATIONS = ("add_letter", "remove_letter", "swap_letters", "add_space",
              "remove_space", "add_punctuation", "remove_punctuation", "replace_word",
              "line_break", "remove_accent")
REPLACEMENTS = {
    "Catalan": ("ahir", "demà", "avi", "carrer", "taula", "veure", "menjar", "però", "mai"),
    "Spanish": ("ayer", "mañana", "abuelo", "calle", "mesa", "ver", "comer", "pero", "nunca"),
}


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def alter(text, operation, rng, language):
    """One effective mutation, with sequential coordinates for exact replay."""
    letters = [i for i, c in enumerate(text) if c.isalpha()]
    spaces = [i for i, c in enumerate(text) if c.isspace()]
    punct = [i for i, c in enumerate(text) if unicodedata.category(c).startswith("P")]
    accented = [i for i, c in enumerate(text) if strip_accent(c) != c]
    pairs = [i for i in range(len(text) - 1) if text[i].isalpha() and
             text[i + 1].isalpha() and text[i] != text[i + 1]]
    start = end = 0
    replacement = ""
    if operation == "add_letter":
        start = end = rng.choice(letters)
        replacement = rng.choice("abcdefghijklmnopqrstuvwxyzç")
    elif operation == "remove_letter":
        start = rng.choice(letters)
        end = start + 1
    elif operation == "swap_letters":
        start = rng.choice(pairs)
        end = start + 2
        replacement = text[start:end][::-1]
    elif operation in ("add_space", "line_break"):
        # Include spaces inserted inside words and line breaks between words.
        start = end = rng.choice(letters if operation == "add_space" else spaces + letters)
        replacement = " " if operation == "add_space" else rng.choice(("\n", "\r\n", "\n\n", "\u2028"))
    elif operation == "remove_space":
        start = rng.choice(spaces)
        end = start + 1
    elif operation == "add_punctuation":
        start = end = rng.randrange(len(text) + 1)
        replacement = rng.choice((".", ",", "?", "!", ":", ";", "'", "¿"))
    elif operation == "remove_punctuation":
        if not punct:
            return None
        start = rng.choice(punct)
        end = start + 1
    elif operation == "replace_word":
        match = rng.choice(list(WORDS.finditer(text)))
        start, end = match.span()
        replacement = rng.choice([w for w in REPLACEMENTS[language] if w != match.group()])
    elif operation == "remove_accent":
        if not accented:
            return None
        start = rng.choice(accented)
        end = start + 1
        replacement = strip_accent(text[start])
    else:
        raise ValueError(operation)
    old = text[start:end]
    return text[:start] + replacement + text[end:], {
        "operation": operation, "start": start, "end": end, "before": old, "after": replacement,
    }


def strip_accent(text):
    return "".join(c for c in unicodedata.normalize("NFD", text)
                   if unicodedata.category(c) != "Mn")


def generate(seed=20261013):
    seeds = json.loads(SEEDS.read_text())
    records = []
    for language in ("Catalan", "Spanish"):
        for index, theme in enumerate(seeds):
            original = " ".join(theme[language])
            rng = random.Random(f"{seed}:{language}:{index}")
            # Mild, medium and heavy corruption, including different mutation families.
            count = (2, 4, 6)[index % 3]
            text, changes = original, []
            preferred = OPERATIONS[index % len(OPERATIONS)]
            for number in range(count):
                operation = preferred if number == 0 else rng.choice(OPERATIONS)
                result = alter(text, operation, rng, language)
                if result is None:
                    result = alter(text, "add_letter", rng, language)
                text, change = result
                changes.append(change)
            records.append({
                "id": f"{language[:2].lower()}-{index + 1:03d}", "topic": theme["topic"],
                "source_language": language, "original_sentences": theme[language],
                "original": original, "text": text, "mutations": changes, "seed": seed,
                "severity": ("mild", "medium", "heavy")[index % 3],
            })
    if len(records) != 100 or len({x["original"] for x in records}) != 100:
        raise ValueError("Expected 100 unique passages")
    for record in records:
        if len(record["original_sentences"]) not in (2, 3) or record["text"] == record["original"]:
            raise ValueError("Each passage needs 2–3 original sentences and effective corruption")
        replay = record["original"]
        for change in record["mutations"]:
            if replay[change["start"]:change["end"]] != change["before"]:
                raise ValueError("Mutation coordinates did not replay")
            replay = replay[:change["start"]] + change["after"] + replay[change["end"]:]
        if replay != record["text"]:
            raise ValueError("Mutation replay did not reproduce text")
    return records


def requests(records):
    for record in records:
        for mode in (record["source_language"], "auto"):
            yield {**record, "request_id": record["id"] + ":" + mode,
                   "language": mode, "punctuation": "moderate", "checks": "both", "native": "English"}


def load_runner():
    spec = importlib.util.spec_from_file_location("provider_eval", ROOT / "eval/run.py")
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "eval/fuzz/generated.jsonl")
    parser.add_argument("--seed", type=int, default=20261013)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--provider", choices=("gemini", "openai"), default="gemini")
    parser.add_argument("--model")
    parser.add_argument("--fallback", action="append", default=[], metavar="PROVIDER:MODEL",
                        help="ordered fallback targets after request failures; judgments never trigger fallback")
    parser.add_argument("--tries", type=int, choices=range(1, 4), default=3)
    parser.add_argument("--timeout", type=float, default=30, help="HTTP timeout; >30 s is a diagnostic extension")
    parser.add_argument("--replies", type=Path, default=ROOT / "eval/fuzz/results/replies.jsonl")
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--interval", type=float, default=5.0, help="minimum seconds between request starts")
    parser.add_argument("--retry-errors", action="store_true", help="retry saved failures without repeating successful checks")
    parser.add_argument("--repair-from", type=Path,
                        help="after a complete first replay, collect one same-model repair for withheld results")
    args = parser.parse_args(argv)
    if args.interval < 0 or args.timeout <= 0:
        parser.error("Interval must be nonnegative and timeout must be positive")
    records = generate(args.seed)
    corpus = "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(corpus)
    operations = collections.Counter(c["operation"] for r in records for c in r["mutations"])
    print(json.dumps({"passages": len(records), "requests": 200,
                      "languages": collections.Counter(r["source_language"] for r in records),
                      "operations": operations, "corpus_sha256": digest(corpus)}, ensure_ascii=False))
    if not args.live:
        return 0
    runner = load_runner()
    _, default_model, _ = runner.PROVIDERS[args.provider]
    model = args.model or default_model
    targets = [(args.provider, model)]
    for target in args.fallback:
        provider, separator, fallback_model = target.partition(":")
        if not separator or not fallback_model or provider not in runner.PROVIDERS:
            parser.error("Fallback must be a supported PROVIDER:MODEL")
        if (provider, fallback_model) not in targets:
            targets.append((provider, fallback_model))
    credentials = {}
    for provider, _ in ([] if args.repair_from else targets):
        variable = runner.PROVIDERS[provider][2]
        credentials[provider] = os.environ.get(variable)
        if not credentials[provider]:
            parser.error(f"Missing repository secret {variable}; no live requests made")
    def diagnostic_post(request):
        started = time.monotonic()
        with runner.urllib.request.urlopen(request, timeout=args.timeout) as response:
            payload = json.load(response)
        return payload, time.monotonic() - started
    runner.post = diagnostic_post
    system = (runner.ASSETS / "check_prompt.md").read_text()
    schema_text = (runner.ASSETS / "check_schema.json").read_text()
    schema = json.loads(schema_text)
    prompt_hash = digest(system + "\0" + schema_text)
    args.replies.parent.mkdir(parents=True, exist_ok=True)
    existing = {}
    if args.replies.exists():
        for line in args.replies.read_text().split("\n"):
            if not line:
                continue
            item = json.loads(line)
            if (item["prompt_sha256"], item["corpus_sha256"]) != (prompt_hash, digest(corpus)) or \
                    (not args.repair_from and
                     (item.get("requested_provider", item["provider"]), item.get("requested_model", item["model"])) !=
                     (args.provider, model)):
                parser.error("Resume file belongs to a different provider, model, prompt or corpus")
            identity = item["case"]["request_id"]
            if identity in existing:
                parser.error(f"Duplicate reply request ID: {identity}")
            existing[identity] = item
    def save():
        saved = "".join(json.dumps(existing[k], ensure_ascii=False) + "\n" for k in sorted(existing))
        temporary = args.replies.with_suffix(".tmp")
        temporary.write_text(saved)
        temporary.replace(args.replies)

    pending = [case for case in requests(records) if case["request_id"] not in existing or
               (args.retry_errors and "error" in existing[case["request_id"]])]
    if args.repair_from:
        from fuzz_report import complete_evidence
        engines = complete_evidence(list(existing.values()),
                                    [json.loads(line) for line in args.repair_from.read_text().split("\n") if line], records)
        pending = [item["case"] for identity, item in existing.items()
                   if engines[identity].get("withheld") and "repair" not in item]
        for provider in {existing[case["request_id"]]["provider"] for case in pending}:
            variable = runner.PROVIDERS[provider][2]
            credentials[provider] = os.environ.get(variable)
            if not credentials[provider]:
                parser.error(f"Missing repository secret {variable}; no repair requests made")
    pace_lock = threading.Lock()
    next_start = [0.0]

    def paced_start():
        with pace_lock:
            wait = max(0, next_start[0] - time.monotonic())
            if wait:
                time.sleep(wait)
            next_start[0] = time.monotonic() + args.interval

    def execute(case):
        if args.repair_from:
            entry = dict(existing[case["request_id"]])
            provider, target_model = entry["provider"], entry["model"]
            repair = {"provider": provider, "model": target_model, "attempts": 1}
            paced_start()
            started = time.monotonic()
            try:
                verdict, _ = runner.PROVIDERS[provider][0](target_model, credentials[provider], system, schema,
                                                        runner.user_message({**case, "repair": True}))
                repair["verdict"] = verdict
            except Exception as error:
                repair["error"] = type(error).__name__
                if isinstance(error, urllib.error.HTTPError):
                    repair["http_status"] = error.code
            repair["latency_seconds"] = time.monotonic() - started
            return {**entry, "repair": repair}
        entry = {"case": case, "provider": args.provider, "model": model,
                 "requested_provider": args.provider, "requested_model": model,
                 "timeout_seconds": args.timeout,
                 "prompt_sha256": prompt_hash, "corpus_sha256": digest(corpus)}
        previous = existing.get(case["request_id"], {})
        failures = list(previous.get("failures", []))
        if "error" in previous and not failures:
            failures.append({k: previous[k] for k in ("provider", "model", "error", "http_status", "attempts") if k in previous})
        attempts = 0
        for provider, target_model in targets:
            check = runner.PROVIDERS[provider][0]
            for attempt in range(1, args.tries + 1):
                attempts += 1
                paced_start()
                try:
                    started = time.monotonic()
                    verdict, elapsed = check(target_model, credentials[provider], system, schema, runner.user_message(case))
                    elapsed = time.monotonic() - started
                    entry.update(provider=provider, model=target_model, verdict=verdict,
                                 latency_seconds=elapsed, attempts=attempts, failures=failures)
                    return entry
                except Exception as error:
                    retryable = isinstance(error, (TimeoutError, urllib.error.URLError))
                    status = error.code if isinstance(error, urllib.error.HTTPError) else None
                    if status is not None:
                        # A rate-limited target cannot benefit from immediate retries;
                        # move to the configured fallback and keep the failure evidence.
                        retryable = status in (500, 502, 503, 504)
                    failure = {"provider": provider, "model": target_model, "error": type(error).__name__}
                    if status is not None:
                        failure["http_status"] = status
                    failures.append(failure)
                    if not retryable or attempt == args.tries:
                        break
                    time.sleep((15 if status == 429 else 5) * attempt)
        entry.update(failures[-1], attempts=attempts, failures=failures)
        return entry

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = []
        for case in pending:
            if args.repair_from:
                # Reserve before sending: an interrupted run must not issue the semantic
                # retry again, even if the provider returned before evidence was saved.
                item = existing[case["request_id"]]
                item["repair"] = {"provider": item["provider"], "model": item["model"], "attempts": 1,
                                  "error": "InterruptedBeforeResult"}
                save()
            futures.append(pool.submit(execute, case))
        for number, future in enumerate(concurrent.futures.as_completed(futures), 1):
            item = future.result()
            existing[item["case"]["request_id"]] = item
            # Keep one latest record per request, even if a resumed run is interrupted.
            save()
            print(f"pending {number}/{len(pending)} {item['case']['request_id']} {'ERROR' if 'error' in item else 'answered'}", flush=True)
    summary = {
        "provider": args.provider, "model": model, "prompt_sha256": prompt_hash,
        "corpus_sha256": digest(corpus), "requests": len(existing),
        "answered": sum("verdict" in item for item in existing.values()),
        "errors": sum("error" in item for item in existing.values()),
        "repair_attempts": sum("repair" in item for item in existing.values()),
        "repair_errors": sum("error" in item.get("repair", {}) for item in existing.values()),
        "answer_models": dict(collections.Counter(item["provider"] + ":" + item["model"]
                                                   for item in existing.values() if "verdict" in item)),
        "over_app_timeout": sum(item.get("latency_seconds", 0) > 30 for item in existing.values()),
    }
    args.replies.with_name("run.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

