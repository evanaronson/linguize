#!/usr/bin/env python3
"""Runs the app's prompt and schema against eval/cases.jsonl and reports
judgment mismatches and latency. Exits nonzero when a case fails or a call errors.
This evaluates provider answers; Android tests cover parsing, edits and writeback.

    GEMINI_API_KEY=... python3 eval/run.py
    OPENAI_API_KEY=... python3 eval/run.py --provider openai

Each case may set "language" (as the app's language setting names it; default
auto), "punctuation" (strict, moderate or casual; default moderate), "checks"
(both, fix or naturalize; default both), "status", "answer_language" (the English name the model should give in
its `language` field; empty for unclear), "fix" (a correction is expected or not) and "natural" (a more
natural alternative is expected or not) and "max_fixes" (more fixes than this
means style is being counted as errors) "min_sentences" (fewer means
run-ons were left alone), "assumption" (whether any assumption should be listed)
and "settled" ([about, answer] pairs sent as Settled lines). Focused regressions may
also set "expected_corrected" / "expected_natural" for exact result text and
"expected_fixes" / "expected_natural_changes" for ordered {from, to} pairs
(reason wording is not compared). Omitted fields aren't checked. "natural": false cases are the important ones: they catch the
app nagging about language that is already fine.
"""
import argparse
import hashlib
import http.client
import json
import os
import pathlib
import statistics
import time
import urllib.request
import urllib.error

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"

# Prompt.MAX_OUTPUT_TOKENS and Prompt.TEXT_OPEN/TEXT_CLOSE in the app: what it sends.
MAX_OUTPUT_TOKENS = 8192
TEXT_OPEN, TEXT_CLOSE = "<text>", "</text>"

# Matches Language.all. Contract tests compare this with CheckOptions.kt.
LANGUAGES = {"Catalan": "standard Central Catalan", "Spanish": "Peninsular"}


def one_line(text):
    """Prompt.oneLine: control characters and Unicode line separators become spaces."""
    return "".join(" " if ord(c) < 32 or 127 <= ord(c) <= 159 or c in "\u2028\u2029" else c for c in text)


def user_message(case):
    language = case.get("language", "auto")
    profiles = case.get("writing_profiles", [])
    if not isinstance(profiles, list):
        raise ValueError("writing_profiles must be a list of complete profiles")
    for profile in profiles:
        if not isinstance(profile, dict) or set(profile) != {"id", "name", "language", "variety", "register", "punctuation"}:
            raise ValueError("Each writing profile must supply all six fields")
        if profile["register"] not in ("casual", "everyday", "formal") or profile["punctuation"] not in ("casual", "moderate", "strict"):
            raise ValueError("Unknown writing profile register or punctuation")
    if len({p["language"] for p in profiles}) != len(profiles):
        raise ValueError("Only one active writing profile per language is allowed")
    if language != "auto" and not profiles:
        language = f"{language} ({LANGUAGES[language]})"
    native = case.get("native", "English")
    settled = "".join(
        "Settled: " + json.dumps({"about": one_line(about), "answer": one_line(answer)},
                                 ensure_ascii=False, separators=(",", ":")) + "\n"
        for about, answer in case.get("settled", [])
    )
    profile_line = ("Writing profiles: " + json.dumps(profiles, ensure_ascii=False, separators=(",", ":"))
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\n") if profiles else ""
    return (f"Language: {language}\n"
            f"Punctuation: {case.get('punctuation', 'moderate')}\n"
            + profile_line + f"Checks: {case.get('checks', 'both')}\n"
            f"Native: {native} (write meaning, assumptions and reasons in {native})\n"
            + settled + ("Repair: true\n" if case.get("repair") else "")
            + f"Text: {TEXT_OPEN}{case['text']}{TEXT_CLOSE}")


def json_object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def json_array(value, label):
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a JSON array")
    return value


def check_gemini(model, key, system, schema, message):
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": message}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseJsonSchema": schema,
            "maxOutputTokens": MAX_OUTPUT_TOKENS,
            "thinkingConfig": {"thinkingLevel": "MINIMAL"},
        },
    }
    request = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
    )
    try:
        payload, elapsed = post(request)
    except urllib.error.HTTPError as error:
        # Mirrors GeminiClient's DroppableOption for models without thinkingLevel.
        if error.code != 400 or b"thinking" not in error.read().lower():
            raise
        del body["generationConfig"]["thinkingConfig"]
        request = urllib.request.Request(request.full_url, data=json.dumps(body).encode(), headers=request.headers)
        payload, elapsed = post(request)
    payload = json_object(payload, "Gemini response")
    candidates = json_array(payload.get("candidates", []), "Gemini candidates")
    if not candidates:
        feedback = json_object(payload.get("promptFeedback", {}), "Gemini prompt feedback")
        if feedback.get("blockReason") is not None:
            raise ValueError(f"Gemini declined the check ({feedback['blockReason']})")
        raise ValueError("Gemini returned no candidate")
    candidate = json_object(candidates[0], "Gemini candidate")
    finish = candidate.get("finishReason")
    # GeminiClient accepts only STOP, including when another finish carries valid JSON.
    if finish != "STOP":
        raise ValueError(f"Gemini did not complete the check ({finish or 'missing finishReason'})")
    content = json_object(candidate.get("content", {}), "Gemini content")
    parts = [json_object(part, "Gemini part") for part in json_array(content.get("parts", []), "Gemini parts")]
    answer = "".join(part.get("text", "") for part in parts if part.get("thought") is not True)
    if not answer.strip():
        raise ValueError("Gemini returned no answer text")
    return json.loads(answer), elapsed


def check_openai(model, key, system, schema, message):
    # Mirrors OpenAIClient: strict mode rejects Gemini's propertyOrdering.
    schema = {k: v for k, v in schema.items() if k != "propertyOrdering"}
    body = {
        "model": model,
        "instructions": system,
        "input": message,
        "store": False,
        "max_output_tokens": MAX_OUTPUT_TOKENS,
        "reasoning": {"effort": "low" if model == "gpt-6.1-sol" or model.startswith("gpt-6.1-sol-")\
                          or model == "gpt-6-astra" or model.startswith("gpt-6-astra-") else "none"},
        "text": {"format": {"type": "json_schema", "name": "check", "strict": True, "schema": schema}},
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    payload, elapsed = post(request)
    payload = json_object(payload, "OpenAI response")
    status = payload.get("status")
    # OpenAIClient also rejects incomplete/failed replies before reading output text.
    if status != "completed":
        details = json_object(payload.get("incomplete_details") or {}, "OpenAI incomplete details")
        reason = details.get("reason")
        suffix = f": {reason}" if reason else ""
        raise ValueError(f"OpenAI did not complete the check ({status or 'missing status'}{suffix})")
    parts = []
    for item in json_array(payload.get("output", []), "OpenAI output"):
        item = json_object(item, "OpenAI output item")
        if item.get("type") == "message":
            parts.extend(json_object(part, "OpenAI message part")
                         for part in json_array(item.get("content", []), "OpenAI message content"))
    if any(part.get("type") == "refusal" for part in parts):
        raise ValueError("OpenAI declined the check")
    answer = "".join(part.get("text", "") for part in parts if part.get("type") == "output_text")
    if not answer.strip():
        raise ValueError("OpenAI returned no answer text")
    return json.loads(answer), elapsed


def post(request):
    start = time.monotonic()
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    return payload, time.monotonic() - start


PROVIDERS = {
    "gemini": (check_gemini, "gemini-3.5-flash-lite", "GEMINI_API_KEY"),
    "openai": (check_openai, "gpt-6.1-sol", "OPENAI_API_KEY"),
}


def summarize_answer(case, verdict, elapsed):
    """Finish validation and formatting before printing a successful case report."""
    verdict = json_object(verdict, "Model answer")
    problems = []
    for kind in ("fixes", "natural_changes"):
        for change in json_array(verdict.get(kind, []), kind):
            change = json_object(change, "adjustment")
            for dimension in ("confidence", "impact"):
                score = change.get(dimension)
                if type(score) is not int or score not in range(4):
                    raise ValueError(f"Every adjustment needs integer {dimension} in 0..3")
    if "status" in case and verdict["status"] != case["status"]:
        problems.append(f"status {verdict['status']} != {case['status']}")
    if "answer_language" in case and verdict.get("language", "") != case["answer_language"]:
        problems.append(f"language {verdict.get('language', '')!r} != {case['answer_language']!r}")
    if "fix" in case and verdict["has_errors"] != case["fix"]:
        problems.append(f"fix {verdict['has_errors']} != {case['fix']}")
    if "natural" in case and verdict["more_natural"] != case["natural"]:
        problems.append(f"natural {verdict['more_natural']} != {case['natural']}")
    if "min_sentences" in case:
        sentences = sum(verdict.get("corrected", "").count(mark) for mark in ".?!")
        if sentences < case["min_sentences"]:
            problems.append(f"{sentences} sentences < {case['min_sentences']} (run-ons left alone?)")
    if "assumption" in case and bool(verdict.get("assumptions")) != case["assumption"]:
        problems.append(f"assumptions {verdict.get('assumptions')} (expected {'some' if case['assumption'] else 'none'})")
    if "max_fixes" in case and len(verdict["fixes"]) > case["max_fixes"]:
        problems.append(f"{len(verdict['fixes'])} fixes > {case['max_fixes']} (style counted as errors?)")
    for field in ("corrected", "natural"):
        expected = "expected_" + field
        if expected in case and verdict[field] != case[expected]:
            problems.append(f"{field} {verdict[field]!r} != {case[expected]!r}")
    for field in ("fixes", "natural_changes"):
        expected = "expected_" + field
        if expected in case:
            changes = [{"from": change["from"], "to": change["to"]} for change in verdict[field]]
            if changes != case[expected]:
                problems.append(f"{field} {changes!r} != {case[expected]!r}")
    mark = "MISS " if problems else "ok   "
    lines = [f"{mark}{elapsed:4.1f}s  {case['text']}  [{verdict.get('language', '')}]"]
    for a in verdict.get("assumptions", []):
        lines.append(f"        assumed: {a['about']}: {a['assumed']}  (or {', '.join(a['alternatives'])})")
    if verdict.get("corrected"):
        lines.append(f"            fix: {verdict['corrected']}")
        for change in verdict.get("fixes", []):
            lines.append(f"                 {change['from'] or '+'} -> {change['to']}  ({change['why']}; confidence {change['confidence']}/3; impact {change['impact']}/3)")
    if verdict.get("natural"):
        lines.append(f"        natural: {verdict['natural']}")
        for change in verdict.get("natural_changes", []):
            lines.append(f"                 {change['from'] or '+'} -> {change['to']}  ({change['why']}; confidence {change['confidence']}/3; impact {change['impact']}/3)")
    lines.extend(f"        !! {problem}" for problem in problems)
    return problems, "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=PROVIDERS, default="gemini")
    parser.add_argument("--model", help="override the provider's default model")
    parser.add_argument("--cases", type=pathlib.Path, default=ROOT / "eval/cases.jsonl", help="curated JSON Lines cases")
    args = parser.parse_args(argv)
    check, default_model, key_var = PROVIDERS[args.provider]
    model = args.model or default_model
    key = os.environ.get(key_var)
    if not key:
        parser.error(f"Set {key_var} to run provider evaluations")
    system = (ASSETS / "check_prompt.md").read_text()
    schema_text = (ASSETS / "check_schema.json").read_text()
    schema = json.loads(schema_text)
    cases_text = args.cases.read_text()
    cases = [json.loads(line) for line in cases_text.split("\n") if line.strip()]
    if not cases:
        parser.error("The evaluation dataset has no cases")
    prompt_hash = hashlib.sha256((system + "\0" + schema_text).encode()).hexdigest()[:12]
    cases_hash = hashlib.sha256(cases_text.encode()).hexdigest()[:12]
    print(f"Provider: {args.provider} · model: {model} · prompt: {prompt_hash} · cases: {cases_hash}")

    times, failures = [], 0
    for number, case in enumerate(cases, 1):
        try:
            message = user_message(case)
            verdict, elapsed = check(model, key, system, schema, message)
            problems, report = summarize_answer(case, verdict, elapsed)
        except (OSError, http.client.HTTPException, LookupError, TypeError, ValueError, AttributeError) as error:
            # Includes HTTP/network failures and malformed envelopes/answers. Keep later
            # cases running, without counting or printing a partial successful report.
            failures += 1
            label = case.get("text", f"case {number}") if isinstance(case, dict) else f"case {number}"
            print(f"ERROR  {label}\n       {error}")
            continue
        times.append(elapsed)
        failures += bool(problems)
        print(report)

    print(f"\n{len(cases) - failures}/{len(cases)} as expected")
    if times:
        times.sort()
        p90 = times[min(len(times) - 1, int(len(times) * 0.9))]
        print(f"median {statistics.median(times):.2f}s · p90 {p90:.2f}s · max {times[-1]:.2f}s")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

