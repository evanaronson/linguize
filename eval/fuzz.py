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
    parser.add_argument("--replies", type=Path, default=ROOT / "eval/fuzz/results/replies.jsonl")
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args(argv)
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
    check, default_model, key_variable = runner.PROVIDERS[args.provider]
    key = os.environ.get(key_variable)
    if not key:
        parser.error(f"Missing repository secret {key_variable}; no live requests made")
    model = args.model or default_model
    system = (runner.ASSETS / "check_prompt.md").read_text()
    schema_text = (runner.ASSETS / "check_schema.json").read_text()
    schema = json.loads(schema_text)
    prompt_hash = digest(system + "\0" + schema_text)
    args.replies.parent.mkdir(parents=True, exist_ok=True)
    existing = {}
    if args.replies.exists():
        for line in args.replies.read_text().splitlines():
            item = json.loads(line)
            if (item["provider"], item["model"], item["prompt_sha256"], item["corpus_sha256"]) != (
                    args.provider, model, prompt_hash, digest(corpus)):
                parser.error("Resume file belongs to a different provider, model, prompt or corpus")
            existing[item["case"]["request_id"]] = item
    pending = [case for case in requests(records) if case["request_id"] not in existing]

    def execute(case):
        entry = {"case": case, "provider": args.provider, "model": model,
                 "prompt_sha256": prompt_hash, "corpus_sha256": digest(corpus)}
        try:
            verdict, elapsed = check(model, key, system, schema, runner.user_message(case))
            entry.update(verdict=verdict, latency_seconds=elapsed)
        except Exception as error:
            # Never write request headers, credential values or raw error response bodies.
            entry["error"] = type(error).__name__
            if isinstance(error, urllib.error.HTTPError):
                entry["http_status"] = error.code
        return entry

    with args.replies.open("a") as output, concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(execute, case) for case in pending]
        for number, future in enumerate(concurrent.futures.as_completed(futures), len(existing) + 1):
            item = future.result()
            output.write(json.dumps(item, ensure_ascii=False) + "\n")
            output.flush()
            existing[item["case"]["request_id"]] = item
            print(f"{number}/200 {item['case']['request_id']} {'ERROR' if 'error' in item else 'answered'}", flush=True)
    summary = {
        "provider": args.provider, "model": model, "prompt_sha256": prompt_hash,
        "corpus_sha256": digest(corpus), "requests": len(existing),
        "answered": sum("verdict" in item for item in existing.values()),
        "errors": sum("error" in item for item in existing.values()),
    }
    args.replies.with_name("run.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
