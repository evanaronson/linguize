#!/usr/bin/env python3
"""Retrospective, display-only assessments. No app code or source history is modified."""
import argparse
import collections
import csv
import datetime
import hashlib
import html
import http.client
import json
import os
from pathlib import Path
import random
import re
import statistics

import run as provider_eval

HERE = Path(__file__).resolve().parent
RUBRIC = HERE / "scoring/rubric.md"
SCHEMA = HERE / "scoring/answer_schema.json"
VERSION = "suggestion-assessment-v2"
CONTEXT_FIELDS = ("requestedLanguage", "targetLanguage", "detectedLanguage", "nativeLanguage",
                  "punctuation", "settled", "variety", "register")
CONFIDENCE = ("Unsupported", "Weak", "Moderate", "Strong")
IMPACT = ("No clear benefit", "Minor", "Substantial", "Meaning-critical")


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def load_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_new(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(text)


def write_lines(path, records):
    write_new(path, "".join(canonical(record) + "\n" for record in records))


def integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"{label} must be an integer in {low}..{high}")
    return value


def assessable(case):
    """Whitelist inputs: source reason, decisions and reference labels cannot bias scoring."""
    message = {key: case[key] for key in ("original", "kind", "start", "end", "from", "to", "context")}
    if case["kind"] == "natural":
        message["assessmentInput"] = case.get("assessmentInput")
        message["unassessableReason"] = unassessable_reason(case)
    return message


def unassessable_reason(case):
    if case["kind"] == "natural" and case.get("assessmentInput") is None:
        return case.get("unassessableReason") or "Corrected baseline and exact natural change unavailable"
    return None


def validate_case(case):
    if not isinstance(case, dict) or not isinstance(case.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", case["id"]):
        raise ValueError("Case id must be 1..80 ASCII letters/digits/hyphens/underscores, starting with a letter or digit")
    for key in ("original", "from", "to"):
        if not isinstance(case.get(key), str):
            raise ValueError(f"{case['id']}: {key} must be text")
    if case.get("kind") not in ("fix", "natural"):
        raise ValueError(f"{case['id']}: unsupported adjustment kind")
    start = integer(case.get("start"), 0, len(case["original"]), "start")
    end = integer(case.get("end"), start, len(case["original"]), "end")
    if case["original"][start:end] != case["from"] or case["from"] == case["to"]:
        raise ValueError(f"{case['id']}: adjustment must match its original span and change something")
    basis = case.get("assessmentInput")
    if basis is not None:
        if case["kind"] != "natural" or not isinstance(basis, dict) or set(basis) != {"baseline", "start", "end", "from", "to"}:
            raise ValueError("Natural assessmentInput must contain the exact baseline adjustment")
        for key in ("baseline", "from", "to"):
            if not isinstance(basis[key], str):
                raise ValueError("Natural baseline and replacement must be text")
        left = integer(basis["start"], 0, len(basis["baseline"]), "baseline start")
        right = integer(basis["end"], left, len(basis["baseline"]), "baseline end")
        if basis["baseline"][left:right] != basis["from"] or basis["from"] == basis["to"]:
            raise ValueError("Natural adjustment must match and change its corrected baseline")
    if "unassessableReason" in case and (not isinstance(case["unassessableReason"], str) or not case["unassessableReason"].strip() or basis is not None):
        raise ValueError("An unavailable baseline needs a nonblank reason and no assessmentInput")
    context = case.get("context")
    if not isinstance(context, dict) or set(context) - set(CONTEXT_FIELDS):
        raise ValueError(f"{case['id']}: unknown or missing context shape")
    for key, value in context.items():
        if key == "settled":
            if not isinstance(value, list) or any(not isinstance(s, dict) or
                    set(s) != {"about", "answer"} or any(not isinstance(v, str) for v in s.values()) for s in value):
                raise ValueError("settled must contain exact about/answer string pairs")
        elif value is not None and not isinstance(value, str):
            raise ValueError(f"{key} must be text or null")
    return case


def cases_from(path):
    cases = [validate_case(case) for case in load_lines(path)]
    if not cases or len({c["id"] for c in cases}) != len(cases):
        raise ValueError("Cases must be nonempty with unique ids")
    return cases


def utf16_index(text, offset):
    """Android uses UTF-16 offsets; reject offsets bisecting a surrogate pair."""
    integer(offset, 0, len(text.encode("utf-16-le")) // 2, "Android offset")
    try:
        return len(text.encode("utf-16-le")[:offset * 2].decode("utf-16-le"))
    except UnicodeDecodeError as error:
        raise ValueError("Android offset splits a surrogate pair") from error


def located_changes(base, target, changes):
    """Conservative exact ordered placement; never choose among repeated spans.

    Both complete texts must be reproduced. Unlike the Android engine, no Unicode
    normalization, lexical trimming or fallback repairs are inferred here.
    """
    if not isinstance(changes, list):
        raise ValueError("Missing exact change list")
    source = destination = 0
    spans = []
    for change in changes:
        if not isinstance(change, dict) or any(not isinstance(change.get(k), str) for k in ("from", "to")):
            raise ValueError("Malformed exact change")
        before, after = change["from"], change["to"]
        if before == after:
            continue
        shared = 0
        while source + shared < len(base) and destination + shared < len(target) and base[source + shared] == target[destination + shared]:
            shared += 1
        matches = [at for at in range(source, source + shared + 1)
                   if base.startswith(before, at) and target.startswith(after, destination + at - source)]
        if len(matches) != 1:
            raise ValueError("Ambiguous or unmatched exact change")
        at = matches[0]
        target_at = destination + at - source
        spans.append((at, at + len(before), target_at, target_at + len(after), after))
        source, destination = at + len(before), target_at + len(after)
    if base[source:] != target[destination:]:
        raise ValueError("Incomplete exact change list")
    return spans


def natural_assessment_input(original, attempt, suggestion, start, end):
    """Match recorded original-anchored evidence to one raw corrected-text decision."""
    raw = attempt.get("raw")
    verdict = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(verdict, dict) or verdict.get("status") != "ok":
        raise ValueError("Successful raw verdict unavailable")
    if type(verdict.get("has_errors")) is not bool:
        raise ValueError("Corrected baseline unavailable")
    baseline = verdict.get("corrected") if verdict["has_errors"] else original
    if not isinstance(baseline, str) or not isinstance(verdict.get("natural"), str):
        raise ValueError("Complete baseline or natural text unavailable")
    fixes = located_changes(original, baseline, verdict.get("fixes"))
    naturals = located_changes(baseline, verdict["natural"], verdict.get("natural_changes"))
    matches = []
    for left, right, _, _, replacement in naturals:
        # Only exact boundaries are supported. Android may trim or widen a
        # decision; reproducing those policies here would invent a second engine.
        if any(fix_left < left < fix_right or fix_left < right < fix_right
               for _, _, fix_left, fix_right, _ in fixes):
            continue
        def original_position(position):
            delta = 0
            for fix_start, fix_end, fix_left, fix_right, _ in fixes:
                if position <= fix_left:
                    return position - (fix_left - fix_start)
                delta = fix_right - fix_end
            return position - delta
        mapped_start, mapped_end = original_position(left), original_position(right)
        if (mapped_start, mapped_end, replacement) == (start, end, suggestion["toText"]):
            matches.append({"baseline": baseline, "start": left, "end": right,
                            "from": baseline[left:right], "to": replacement})
    if len(matches) != 1:
        raise ValueError("No unique exact natural decision matches the recorded edit")
    return matches[0]


def history_cases(rows):
    cases = []
    kinds = {"fix": "fix", "natural": "natural", "Fix": "fix", "Natural": "natural"}
    for row in rows:
        session, suggestions = row["session"], row["suggestions"]
        original = session["text"]
        attempts = session["attempts"]
        if isinstance(attempts, str):
            attempts = json.loads(attempts)
        for suggestion in suggestions:
            index = integer(suggestion["attempt"], 0, len(attempts) - 1, "attempt index")
            attempt = attempts[index]
            # Session settings describe the latest attempt: never borrow them for a
            # current-schema earlier attempt. Only old schemas used session-wide settings.
            context = {}
            for key in CONTEXT_FIELDS:
                if key == "settled":
                    context[key] = attempt.get(key, [])
                elif key in attempt:
                    context[key] = attempt[key]
                elif session.get("schema", 0) <= 3:
                    context[key] = session.get(key)
                else:
                    context[key] = None
            # Resolve only profiles actually recorded for THIS attempt. Never apply
            # present-day settings, a later attempt, or missing defaults to old evidence.
            profiles = attempt.get("writingProfiles") or []
            if not isinstance(profiles, list):
                raise ValueError("Historical writingProfiles must be a list")
            target = context.get("targetLanguage") or context.get("detectedLanguage")
            matching = [p for p in profiles if isinstance(p, dict) and p.get("language") == target]
            if len(matching) == 1:
                for key in ("register", "punctuation", "variety"):
                    context[key] = matching[0].get(key)
            start = utf16_index(original, suggestion["start"])
            end = utf16_index(original, suggestion["end"])
            case = {
                "id": digest([session["id"], suggestion["id"], index])[:24],
                "original": original, "kind": kinds[suggestion["kind"]],
                "start": start, "end": end, "from": suggestion["fromText"], "to": suggestion["toText"],
                "context": context,
                "source": {"type": "history", "sessionId": session["id"], "suggestionId": suggestion["id"],
                           "attempt": index, "androidStart": suggestion["start"], "androidEnd": suggestion["end"],
                           "provider": attempt.get("provider"), "model": attempt.get("model"),
                           "promptHash": attempt.get("promptHash")},
                "originalReason": suggestion.get("why"),
            }
            if case["kind"] == "natural":
                try:
                    case["assessmentInput"] = natural_assessment_input(original, attempt, suggestion, start, end)
                except (ValueError, TypeError, KeyError):
                    # Keep the original evidence, without guessing at a baseline.
                    case["unassessableReason"] = "No reliable corrected baseline and uniquely matched natural decision in this attempt's raw verdict"
            cases.append(validate_case(case))
    if not cases or len({c["id"] for c in cases}) != len(cases):
        raise ValueError("Export needs suggestions with unique session/suggestion identities")
    return cases


def validate_answer(answer):
    if not isinstance(answer, dict) or set(answer) != {"confidence", "impact", "evidence"}:
        raise ValueError("Assessment must contain exactly confidence, impact and evidence")
    for key in ("confidence", "impact"):
        integer(answer[key], 0, 3, key)
    if not isinstance(answer["evidence"], str) or not answer["evidence"].strip() or len(answer["evidence"]) > 400:
        raise ValueError("Evidence must be 1..400 characters of nonblank text")
    return answer


def rubric_contract():
    system, schema = RUBRIC.read_text(encoding="utf-8"), json.loads(SCHEMA.read_text(encoding="utf-8"))
    return system, schema, digest([system, schema])


def score(cases, out, provider, model, repeats):
    check, default_model, key_name = provider_eval.PROVIDERS[provider]
    key = os.environ.get(key_name)
    if not key:
        raise ValueError(f"Configure {key_name} in the environment to run live assessments")
    system, schema, rubric_hash = rubric_contract()
    model = model or default_model
    dataset_hash = digest([assessable(c) for c in cases])
    # Exclusive creation avoids mixing runs or overwriting input evidence.
    out.parent.mkdir(parents=True, exist_ok=True)
    failures = 0
    with out.open("x", encoding="utf-8") as stream:
        for repeat in range(repeats):
            order = list(cases)
            random.Random(repeat).shuffle(order)
            for case in order:
                record = {"id": case["id"], "caseHash": digest(assessable(case)),
                          "datasetHash": dataset_hash, "rubricVersion": VERSION, "rubricHash": rubric_hash,
                          "provider": provider, "model": model, "repeat": repeat,
                          "assessedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          "origin": "retrospective", "assessment": None}
                try:
                    if unassessable_reason(case):
                        record["unassessableReason"] = unassessable_reason(case)
                        raise ValueError("Historical natural decision unavailable")
                    answer, elapsed = check(model, key, system, schema, canonical(assessable(case)))
                    record["rawAnswer"] = answer
                    record["latencySeconds"] = elapsed
                    record["assessment"] = validate_answer(answer)
                except (OSError, http.client.HTTPException, LookupError, TypeError, ValueError, AttributeError) as error:
                    # A failed assessment stays null, never becomes a numerical zero.
                    # Avoid printing HTTP bodies, private input, or credentials.
                    record["error"] = type(error).__name__
                    failures += 1
                stream.write(canonical(record) + "\n")
                stream.flush()
                print(f"{case['id']} repeat {repeat + 1}: {'failed' if record['assessment'] is None else 'assessed'}")
    return 1 if failures else 0


def read_references(path, cases):
    if path is None:
        return {}
    expected = {c["id"]: digest(assessable(c)) for c in cases}
    result = {}
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    for row in rows:
        key = row["id"]
        if key not in expected or row["caseHash"] != expected[key] or key in result:
            raise ValueError("Reference ids/hashes must uniquely match this dataset")
        for name in ("confidence", "impact"):
            row[name] = None if row[name] == "" else integer(int(row[name]), 0, 3, name)
        if row["justified"] not in ("", "yes", "no", "uncertain"):
            raise ValueError("Reference justified must be yes/no/uncertain or blank")
        if any(row[k] is not None for k in ("confidence", "impact")) or row["justified"]:
            case = next(c for c in cases if c["id"] == key)
            if unassessable_reason(case):
                raise ValueError("Cannot reference-rate a natural edit without its corrected baseline")
            if not row.get("reviewer", "").strip():
                raise ValueError("Completed reference judgments require a reviewer identity")
        result[key] = row
    return result


def summarize(cases, results, references):
    grouped = collections.defaultdict(list)
    expected = {c["id"]: digest(assessable(c)) for c in cases}
    dataset_hash = digest([assessable(c) for c in cases])
    seen, identities = set(), set()
    failures = 0
    for record in results:
        if record["id"] not in expected or record["caseHash"] != expected[record["id"]] or record["datasetHash"] != dataset_hash:
            raise ValueError("Results do not match the exact input dataset")
        identity = (record["provider"], record["model"], record["rubricVersion"], record["rubricHash"])
        identities.add(identity)
        integer(record["repeat"], 0, 19, "repeat")
        key = record["id"], record["repeat"]
        if key in seen:
            raise ValueError("Duplicate case/repeat assessment; compare runs separately")
        seen.add(key)
        if record.get("assessment") is None:
            if not unassessable_reason(next(c for c in cases if c["id"] == record["id"])):
                failures += 1
            continue
        if unassessable_reason(next(c for c in cases if c["id"] == record["id"])):
            raise ValueError("Cannot assess a natural edit without its corrected baseline")
        grouped[record["id"]].append(validate_answer(record["assessment"]))
    if len(identities) > 1:
        raise ValueError("Compare different assessor/rubric runs separately")
    if results and next(iter(identities))[2:] != (VERSION, rubric_contract()[2]):
        raise ValueError("Results use a different rubric; use its matching tool revision")
    repeats = [answers for answers in grouped.values() if len(answers) >= 2]
    stable = sum(len({(a["confidence"], a["impact"]) for a in answers}) == 1 for answers in repeats)
    bins = []
    for level in range(4):
        yes = no = 0
        for key, answers in grouped.items():
            ref = references.get(key, {})
            if ref.get("justified") in ("yes", "no"):
                # One outcome per case: repeated calls are not independent labeled cases.
                confidence = statistics.median_low(a["confidence"] for a in answers)
                if confidence == level:
                    yes += ref["justified"] == "yes"
                    no += ref["justified"] == "no"
        bins.append({"confidence": level, "reviewedCases": yes + no, "justified": yes,
                     "unjustified": no, "justifiedFraction": yes / (yes + no) if yes + no else None})
    agreement = {}
    for field in ("confidence", "impact"):
        pairs = [(statistics.median_low(a[field] for a in answers), references[key][field])
                 for key, answers in grouped.items() if references.get(key, {}).get(field) is not None]
        matrix = [[0] * 4 for _ in range(4)]
        for actual, reference in pairs:
            matrix[reference][actual] += 1
        agreement[field] = {"cases": len(pairs), "exactMatches": sum(a == b for a, b in pairs),
                            "matrixReferenceRowsAssessorColumns": matrix}
    return {"cases": len(cases), "successfulCalls": sum(map(len, grouped.values())),
            "failedCalls": failures, "assessedCases": len(grouped),
            "unassessableCases": sum(bool(unassessable_reason(c)) for c in cases),
            "repeatedCases": len(repeats), "stableRepeatedCases": stable,
            "confidenceBins": bins, "referenceAgreement": agreement,
            "note": "Ordinal exploratory assessments, not calibrated probabilities. Blank references establish no accuracy.",
            "runIdentity": list(next(iter(identities))) if identities else None}


def escaped(value):
    return html.escape(str(value), quote=True)


def report(cases, results, references, out):
    summary = summarize(cases, results, references)
    grouped = collections.defaultdict(list)
    for record in results:
        grouped[record["id"]].append(record)
    out.mkdir(parents=True, exist_ok=False)
    for blind in (True, False):
        rows = []
        for case in cases:
            original, start, end = case["original"], case["start"], case["end"]
            changed = original[:start] + case["to"] + original[end:]
            cells = [case["id"], case["kind"], canonical(case["context"]), original, changed,
                     f"{case['from']!r} → {case['to']!r} (span {start}:{end})"]
            if case["kind"] == "natural":
                basis = case.get("assessmentInput")
                if basis is None:
                    assessment_context = "NOT ASSESSABLE: " + unassessable_reason(case)
                else:
                    after = basis["baseline"][:basis["start"]] + basis["to"] + basis["baseline"][basis["end"]:]
                    assessment_context = ("Corrected baseline: " + basis["baseline"] + "\nOnly this natural decision applied: " + after +
                                          f"\nExact natural decision: {basis['from']!r} → {basis['to']!r} (span {basis['start']}:{basis['end']})" +
                                          "\nAssess only additional benefit beyond necessary fixes.")
            else:
                assessment_context = "Assess the original adjustment above."
            cells.append(assessment_context)
            if not blind:
                assessments = grouped[case["id"]]
                cells.append("\n".join(f"Repeat {r['repeat'] + 1}: " + (
                    f"confidence {r['assessment']['confidence']}/3 ({CONFIDENCE[r['assessment']['confidence']]}), "
                    f"impact {r['assessment']['impact']}/3 ({IMPACT[r['assessment']['impact']]}): "
                    + r['assessment']['evidence'] if r.get('assessment') else "UNASSESSED / failed")
                    for r in assessments) or "UNASSESSED / pending")
            rows.append("<tr>" + "".join(f"<td>{escaped(c)}</td>" for c in cells) + "</tr>")
        headings = ["ID", "Kind", "Available context", "Original evidence", "Original anchored replacement applied", "Recorded adjustment", "Assessment context"]
        if not blind:
            headings.append("Model assessments")
        title = "Blind reference review" if blind else "Exploratory suggestion assessments"
        page = '<!doctype html><html lang="en"><meta charset="utf-8"><title>' + title + '</title>'
        page += '<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:16px system-ui;margin:24px}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:12px;text-align:left;vertical-align:top;white-space:pre-wrap}th{background:#eee}</style>'
        page += f'<h1>{title}</h1><p>Rubric: {VERSION}. Scores do not filter or apply changes. '
        page += 'Independent reviewers: use blind.html and references.csv before viewing review.html. '
        page += 'Original model reasons and user acceptance decisions are omitted.</p>'
        page += '<details><summary>Assessment rubric and scale definitions</summary><pre style="white-space:pre-wrap">' + escaped(RUBRIC.read_text(encoding="utf-8")) + '</pre></details>'
        if not blind:
            page += '<p>Assessor identity (provider, model, rubric version/hash): ' + escaped(summary['runIdentity']) + '</p>'
            page += '<p>Assessed cases: ' + str(summary['assessedCases']) + '; unassessable cases: ' + str(summary['unassessableCases']) + '; failed calls: ' + str(summary['failedCalls']) + '; cases with at least two successful assessments: ' + str(summary['repeatedCases']) + '.</p>'
            if not any(ref.get('reviewer') for ref in references.values()):
                page += '<p><strong>No independent reference judgments supplied. These scores do not establish correctness or calibration.</strong></p>'
        page += '<table><thead><tr>'
        page += ''.join(f'<th>{h}</th>' for h in headings) + '</tr></thead><tbody>' + ''.join(rows) + '</tbody></table></html>'
        (out / ("blind.html" if blind else "review.html")).write_text(page, encoding="utf-8")
    with (out / "references.csv").open("w", newline="", encoding="utf-8") as stream:
        fields = ["id", "caseHash", "reviewer", "justified", "confidence", "impact", "reason"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for case in cases:
            # Only opaque hex ids/hashes are emitted; no untrusted text/formulas in CSV.
            writer.writerow({"id": case["id"], "caseHash": digest(assessable(case))})
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("prepare", help="Convert an app JSONL History export into exact adjustments")
    prepare.add_argument("--history", type=Path, required=True)
    prepare.add_argument("--out", type=Path, required=True)
    scoring = sub.add_parser("score", help="Make live provider assessments (billed network requests)")
    scoring.add_argument("--cases", type=Path, required=True)
    scoring.add_argument("--out", type=Path, required=True)
    scoring.add_argument("--provider", choices=provider_eval.PROVIDERS, default="gemini")
    scoring.add_argument("--model")
    scoring.add_argument("--repeats", type=int, default=3)
    reporting = sub.add_parser("report", help="Produce blind and scored HTML, reference CSV and summary")
    reporting.add_argument("--cases", type=Path, required=True)
    reporting.add_argument("--results", type=Path)
    reporting.add_argument("--references", type=Path)
    reporting.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            cases = history_cases(load_lines(args.history))
            write_lines(args.out, cases)
            print(f"Prepared {len(cases)} exact historical adjustments")
        elif args.command == "score":
            if not 1 <= args.repeats <= 20:
                raise ValueError("repeats must be 1..20")
            return score(cases_from(args.cases), args.out, args.provider, args.model, args.repeats)
        else:
            cases = cases_from(args.cases)
            references = read_references(args.references, cases)
            results = load_lines(args.results) if args.results else []
            print(json.dumps(report(cases, results, references, args.out), indent=2))
    except (OSError, LookupError, ValueError, TypeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
