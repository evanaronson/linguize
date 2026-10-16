#!/usr/bin/env python3
"""Build a review sheet from provider evidence and production-engine replay."""
import argparse
import collections
import html
import hashlib
import json
from pathlib import Path
from fuzz import requests


def evidence_digest(value):
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


def matching_review(reply, engine, note):
    """A new model answer or changed engine output must receive a fresh review."""
    if note and all(note.get(field) == reply.get(field) for field in
                    ("provider", "model", "prompt_sha256", "corpus_sha256")) and \
            note.get("verdict_sha256") == evidence_digest(reply.get("verdict")) and \
            ("repair" not in reply and "repair_sha256" not in note or
             note.get("repair_sha256") == evidence_digest(reply.get("repair"))) and \
            note.get("engine_sha256") == evidence_digest(engine):
        return note
    return {"review_status": "not_reviewed_for_this_answer"}


def complete_evidence(replies, engine_rows, corpus, require_repairs=False, repair_collection=False, legacy_unbound=False):
    """Final audit gate; partial provider collection remains resumable elsewhere."""
    if legacy_unbound and (require_repairs or repair_collection):
        raise ValueError("Historical unbound replay cannot support live audit or repair collection")
    if len(corpus) != 100 or len({row["id"] for row in corpus}) != 100 or \
            len({row["original"] for row in corpus}) != 100 or \
            len({row["text"] for row in corpus}) != 100 or \
            collections.Counter(row["source_language"] for row in corpus) != {"Catalan": 50, "Spanish": 50} or \
            any(len(row["original_sentences"]) not in (2, 3) or row["text"] == row["original"] for row in corpus):
        raise ValueError("Audit needs 100 distinct corrupted passages of 2–3 sentences, 50 per language")
    expected = {case["request_id"]: case for case in requests(corpus)}

    def indexed(rows, key, label):
        result = {}
        for row in rows:
            identity = key(row)
            if identity in result:
                raise ValueError(f"Duplicate {label} request ID: {identity}")
            result[identity] = row
        if result.keys() != expected.keys():
            raise ValueError(f"{label} must contain every corpus request exactly once; "
                             f"missing={sorted(expected.keys() - result.keys())}, "
                             f"unexpected={sorted(result.keys() - expected.keys())}")
        return result

    answers = indexed(replies, lambda row: row["case"]["request_id"], "Replies")
    engines = indexed(engine_rows, lambda row: row["request_id"], "Replay")
    identity_fields = ("provider", "model", "prompt_sha256", "corpus_sha256")
    for identity, case in expected.items():
        reply = answers[identity]
        if reply["case"] != case:
            raise ValueError(f"Reply case differs from corpus request: {identity}")
        if "error" in reply or not isinstance(reply.get("verdict"), dict) or not reply["verdict"]:
            raise ValueError(f"Audit requires a returned verdict for every request: {identity}")
        if engines[identity].get("result") not in ("reviewed", "unclear", "wrong_language"):
            raise ValueError(f"Audit requires a successful replay for every request: {identity}")
        if not (legacy_unbound and "replay_input" not in engines[identity]) and any(
                not isinstance(reply.get(key), str) or not reply[key].strip() for key in identity_fields):
            raise ValueError(f"Audit requires nonempty provider, model and prompt/corpus hashes: {identity}")
        repair = reply.get("repair")
        if repair is not None and (repair.get("provider") != reply["provider"] or repair.get("model") != reply["model"] or
                                   repair.get("attempts") != 1 or ("verdict" in repair) == ("error" in repair)):
            raise ValueError(f"Repair must record one same-provider/model answer or failure: {identity}")
        retry_evidence = engines[identity].get("retry_evidence")
        if require_repairs and (retry_evidence not in ("recorded", "not_needed") or
                                retry_evidence == "recorded" and repair is None):
            raise ValueError(f"Final live audit requires actual repair evidence: {identity}")
        # An initial replay remains usable to resume repair collection: rows with an
        # already recorded attempt never consult its withheld flags or send again.
        # Reports always need replay evidence for the current answer AND repair.
        if not (repair_collection and "repair" in reply):
            if legacy_unbound and "replay_input" not in engines[identity]:
                continue
            source = engines[identity].get("replay_input")
            required_fields = ("case", *identity_fields, "verdict")
            fields = (*required_fields, "repair")
            if not isinstance(source, dict) or any(key not in source for key in required_fields) or \
                    evidence_digest({key: source[key] for key in fields if key in source}) != \
                    evidence_digest({key: reply[key] for key in fields if key in reply}):
                raise ValueError(f"Replay input is unbound or differs from current reply; replay again: {identity}")
    return engines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, default=Path("eval/fuzz/results"))
    parser.add_argument("--reviews", type=Path, default=Path("eval/fuzz/manual_review.json"))
    parser.add_argument("--corpus", type=Path, default=Path("eval/fuzz/generated.jsonl"))
    parser.add_argument("--require-repairs", action="store_true", help="reject simulated retries in a final live audit")
    parser.add_argument("--legacy-unbound", action="store_true",
                        help="inspect historical replay without input provenance; never final live evidence")
    args = parser.parse_args()
    if args.legacy_unbound and args.require_repairs:
        parser.error("--legacy-unbound cannot support --require-repairs")
    reviews = {}
    if args.reviews.exists():
        rows = json.loads(args.reviews.read_text())
        reviews = {row["request_id"]: row for row in rows}
        if len(reviews) != len(rows):
            raise ValueError("Duplicate manual review IDs")
    replies = [json.loads(line) for line in (args.results / "replies.jsonl").read_text().split("\n") if line]
    engine_rows = [json.loads(line) for line in (args.results / "engine.jsonl").read_text().split("\n") if line]
    corpus = [json.loads(line) for line in args.corpus.read_text().split("\n") if line]
    engines = complete_evidence(replies, engine_rows, corpus, args.require_repairs, legacy_unbound=args.legacy_unbound)
    joined = []
    for reply in sorted(replies, key=lambda r: r["case"]["request_id"]):
        case = reply["case"]
        engine = engines[case["request_id"]]
        review = matching_review(reply, engine, reviews.get(case["request_id"]))
        joined.append({**reply, "engine": engine, "manual_review": review,
                       "replay_input_binding": "bound" if "replay_input" in engine else "legacy_unbound"})
    (args.results / "review.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in joined))
    summary = {
        "requests": len(joined),
        "replay_input_binding": collections.Counter(row["replay_input_binding"] for row in joined),
        "answered": sum("verdict" in row for row in joined),
        "provider_errors": sum("error" in row for row in joined),
        "repair_attempts": sum("repair" in row for row in joined),
        "repair_errors": sum("error" in row.get("repair", {}) for row in joined),
        "retry_evidence": collections.Counter(row["engine"].get("retry_evidence", "unrecorded") for row in joined),
        "answer_models": collections.Counter(row["provider"] + ":" + row["model"] for row in joined if "verdict" in row),
        "manual_reviewed": sum(row["manual_review"].get("review_status") == "reviewed" for row in joined),
        "manual_findings": collections.Counter(code for row in joined for code in row["manual_review"].get("findings", [])),
        "answers_over_app_30s_timeout": sum(row.get("latency_seconds", 0) > 30 for row in joined),
        "results": collections.Counter(row["engine"]["result"] for row in joined),
        "engine_flags": collections.Counter(flag for row in joined for flag in row["engine"].get("flags", [])),
    }
    (args.results / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    sections = []
    for row in joined:
        case, engine = row["case"], row["engine"]
        verdict = engine.get("selected_verdict", row.get("verdict", {}))
        esc = lambda x: html.escape(str(x))
        edits = "".join("<li><strong>" + esc(e["kind"]) + "</strong>: <del>" +
                        esc(e["from"]) + "</del> → " + esc(e["to"]) +
                        "<p>" + esc(e["why"]) + "</p><pre>" +
                        esc(e["accepted_text"]) + "</pre></li>" for e in engine.get("edits", []))
        sections.append("<details id='" + esc(case["request_id"]) + "'><summary>" +
                        esc(case["request_id"]) + " · " + esc(case["topic"]) + " · " +
                        esc(engine["result"]) + " · " + esc(engine.get("flags", [])) +
                        "</summary><h3>Manual review</h3><pre>" + esc(json.dumps(row["manual_review"], ensure_ascii=False, indent=2)) +
                        "</pre><h3>Clean context</h3><pre>" + esc(case["original"]) +
                        "</pre><h3>Damaged input</h3><pre>" + esc(case["text"]) +
                        "</pre><h3>Model correction</h3><pre>" + esc(verdict.get("corrected", "")) +
                        "</pre><h3>Model rewording</h3><pre>" + esc(verdict.get("natural", "")) +
                        "</pre><h3>Accepted fixes</h3><pre>" + esc(engine.get("fix_preview", "")) +
                        "</pre><h3>Accepted rewordings</h3><pre>" + esc(engine.get("natural_preview", "")) +
                        "</pre><h3>Individual Accept actions</h3><ol>" + edits +
                        "</ol><h3>Full evidence</h3><pre>" +
                        esc(json.dumps(row, ensure_ascii=False, indent=2)) + "</pre></details>")
    binding_notice = "<p>Historical unbound replay: these outputs do not record which answers were replayed. " \
        "Their pairing with the current reply file is unverified.</p>" if any(
            row["replay_input_binding"] == "legacy_unbound" for row in joined) else ""
    document = "<!doctype html><meta charset='utf-8'><title>Linguize corruption review</title>" \
        "<style>body{font:16px system-ui;max-width:1100px;margin:32px auto;padding:16px}" \
        "details{border:1px solid #bbb;padding:16px;margin:12px 0}pre{white-space:pre-wrap}" \
        "summary{cursor:pointer}li{margin:16px 0}</style><h1>200 checker results</h1>" + binding_notice + \
        "<p>100 synthetic passages; each checked in its named language and Auto Detect. " \
        "Clean context is not an exact correction oracle. Replies taking over 30 seconds use an extended " \
        "diagnostic timeout; the shipped app would time out. Engine flags record violations, " \
        "so successful evidence capture does not mean the suggestions passed.</p><pre>" + html.escape(json.dumps(summary, indent=2)) + \
        "</pre>" + "".join(sections)
    (args.results / "review.html").write_text(document)
    print(json.dumps(summary))


if __name__ == "__main__":
    main()

