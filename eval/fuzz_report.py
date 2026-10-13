#!/usr/bin/env python3
"""Build a review sheet from provider evidence and production-engine replay."""
import argparse
import collections
import html
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, default=Path("eval/fuzz/results"))
    args = parser.parse_args()
    replies = [json.loads(line) for line in (args.results / "replies.jsonl").read_text().split("\n") if line]
    engines = {row["request_id"]: row for row in (
        json.loads(line) for line in (args.results / "engine.jsonl").read_text().split("\n") if line)}
    joined = []
    for reply in sorted(replies, key=lambda r: r["case"]["request_id"]):
        case = reply["case"]
        engine = engines[case["request_id"]]
        joined.append({**reply, "engine": engine})
    if len(joined) != 200 or len(engines) != 200:
        raise ValueError("Review needs all 200 results")
    (args.results / "review.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in joined))
    summary = {
        "requests": len(joined),
        "results": collections.Counter(row["engine"]["result"] for row in joined),
        "engine_flags": collections.Counter(flag for row in joined for flag in row["engine"].get("flags", [])),
    }
    (args.results / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    sections = []
    for row in joined:
        case, engine, verdict = row["case"], row["engine"], row.get("verdict", {})
        esc = lambda x: html.escape(str(x))
        edits = "".join("<li><strong>" + esc(e["kind"]) + "</strong>: <del>" +
                        esc(e["from"]) + "</del> → " + esc(e["to"]) +
                        "<p>" + esc(e["why"]) + "</p><pre>" +
                        esc(e["accepted_text"]) + "</pre></li>" for e in engine.get("edits", []))
        sections.append("<details id='" + esc(case["request_id"]) + "'><summary>" +
                        esc(case["request_id"]) + " · " + esc(case["topic"]) + " · " +
                        esc(engine["result"]) + " · " + esc(engine.get("flags", [])) +
                        "</summary><h3>Clean context</h3><pre>" + esc(case["original"]) +
                        "</pre><h3>Damaged input</h3><pre>" + esc(case["text"]) +
                        "</pre><h3>Model correction</h3><pre>" + esc(verdict.get("corrected", "")) +
                        "</pre><h3>Model rewording</h3><pre>" + esc(verdict.get("natural", "")) +
                        "</pre><h3>Accepted fixes</h3><pre>" + esc(engine.get("fix_preview", "")) +
                        "</pre><h3>Accepted rewordings</h3><pre>" + esc(engine.get("natural_preview", "")) +
                        "</pre><h3>Individual Accept actions</h3><ol>" + edits +
                        "</ol><h3>Full evidence</h3><pre>" +
                        esc(json.dumps(row, ensure_ascii=False, indent=2)) + "</pre></details>")
    document = "<!doctype html><meta charset='utf-8'><title>Linguize corruption review</title>" \
        "<style>body{font:16px system-ui;max-width:1100px;margin:32px auto;padding:16px}" \
        "details{border:1px solid #bbb;padding:16px;margin:12px 0}pre{white-space:pre-wrap}" \
        "summary{cursor:pointer}li{margin:16px 0}</style><h1>200 checker results</h1>" \
        "<p>100 synthetic passages; each checked in its named language and Auto Detect. " \
        "Clean context is not an exact correction oracle.</p><pre>" + html.escape(json.dumps(summary, indent=2)) + \
        "</pre>" + "".join(sections)
    (args.results / "review.html").write_text(document)
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
