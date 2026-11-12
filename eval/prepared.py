"""Offline bridge to the app's Kotlin check preparation contract.

This module validates exported requests; it never constructs prompt text. JSON
object order is part of the version-1 artifact digest, including schema order.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
MODES = ("app", "base")


class PreparationError(ValueError):
    pass


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(compact(value).encode("utf-8")).hexdigest()


def read_cases(path):
    data = Path(path).read_bytes()
    try:
        cases = [json.loads(line) for line in data.decode("utf-8").split("\n") if line.strip()]
    except (UnicodeError, ValueError) as error:
        raise PreparationError(f"Invalid cases JSONL: {error}") from error
    if not cases or any(not isinstance(case, dict) for case in cases):
        raise PreparationError("The evaluation dataset must contain JSON object cases")
    return cases, hashlib.sha256(data).hexdigest()


def contract_digest(mode, record):
    generation = record["generation"]
    return digest({
        "mode": mode,
        "system": generation["system"],
        "schema": generation["schema"],
        "name": generation["name"],
        "max_tokens": generation["max_tokens"],
        "parser": record["parser"],
    })


def request_digest(record):
    return digest({
        "contract_sha256": record["contract_sha256"],
        "user": record["generation"]["user"],
        "compatibility_hash": record["compatibility_hash"],
    })


def _validate_request(record, mode):
    if not isinstance(record, dict):
        raise PreparationError("A prepared request must be an object")
    generation = record.get("generation")
    if not isinstance(generation, dict) or set(generation) != {
            "system", "user", "schema", "name", "max_tokens"}:
        raise PreparationError("A prepared request needs a complete generation object")
    if any(not isinstance(generation[key], str) for key in ("system", "user", "name")) \
            or not isinstance(generation["schema"], dict) \
            or type(generation["max_tokens"]) is not int or generation["max_tokens"] <= 0:
        raise PreparationError("Invalid prepared generation fields")
    parser = record.get("parser")
    if not isinstance(parser, dict) or list(parser) != ["policy", "prompt_hash"] \
            or parser["policy"] != "base-fresh-v1" \
            or not isinstance(parser["prompt_hash"], str) \
            or not re.fullmatch(r"[0-9a-f]{12}", parser["prompt_hash"]):
        raise PreparationError("Unsupported prepared parser contract")
    if not isinstance(record.get("compatibility_hash"), str) or not record["compatibility_hash"]:
        raise PreparationError("Missing prepared compatibility hash")
    if record.get("contract_sha256") != contract_digest(mode, record):
        raise PreparationError("Prepared contract digest does not match its payload")
    if record.get("request_sha256") != request_digest(record):
        raise PreparationError("Prepared request digest does not match its payload")


def validate_records(records, cases, dataset_sha256, mode):
    if mode not in MODES:
        raise PreparationError(f"Unknown preparation mode: {mode}")
    if len(records) != len(cases):
        raise PreparationError("Prepared artifact does not contain exactly one record per case")
    for index, (record, case) in enumerate(zip(records, cases)):
        if not isinstance(record, dict) or type(record.get("version")) is not int or record["version"] != 1:
            raise PreparationError("Unsupported prepared artifact version")
        if type(record.get("case_index")) is not int or record["case_index"] != index \
                or compact(record.get("case")) != compact(case) or record.get("dataset_sha256") != dataset_sha256:
            raise PreparationError(f"Prepared artifact does not match case {index} and dataset bytes")
        if record.get("mode") != mode:
            raise PreparationError("Prepared artifact mode does not match the requested mode")
        for key in ("initial", "repair"):
            _validate_request(record.get(key), mode)
        if record["initial"]["contract_sha256"] != record["repair"]["contract_sha256"] \
                or record["initial"]["compatibility_hash"] != record["repair"]["compatibility_hash"]:
            raise PreparationError("Initial and repair requests must use the same prepared contract")
    return records


def load_prepared(path, cases_path, mode="app"):
    cases, dataset_sha256 = read_cases(cases_path)
    try:
        records = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").split("\n")
                   if line.strip()]
    except (UnicodeError, ValueError) as error:
        raise PreparationError(f"Invalid prepared JSONL: {error}") from error
    return validate_records(records, cases, dataset_sha256, mode)


def prepare_cases(cases_path, mode="app"):
    """Export one initial/repair pair per case through the actual app contract."""
    if mode not in MODES:
        raise PreparationError(f"Unknown preparation mode: {mode}")
    cases_path = Path(cases_path).resolve()
    cases, dataset_sha256 = read_cases(cases_path)
    with tempfile.TemporaryDirectory(prefix="linguize-check-eval-") as folder:
        output = Path(folder) / "prepared.jsonl"
        command = [
            str(ROOT / "gradlew"), ":app:prepareCheckEval", "--console=plain", "--quiet",
            f"-PcheckEvalCases={cases_path}", f"-PcheckEvalOutput={output}", f"-PcheckEvalMode={mode}",
        ]
        environment = {key: value for key, value in os.environ.items()
                       if key not in {"GEMINI_API_KEY", "OPENAI_API_KEY"}}
        result = subprocess.run(command, cwd=ROOT, text=True, encoding="utf-8",
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False, env=environment)
        if result.returncode:
            raise PreparationError(f"Kotlin check preparation failed ({result.returncode}):\n{result.stdout.strip()}")
        if not output.is_file():
            raise PreparationError("Kotlin check preparation did not write an artifact")
        records = load_prepared(output, cases_path, mode)
        # Detect an input rewrite during Gradle execution as well as mismatched
        # exporter output. A live run must evaluate the dataset it first opened.
        return validate_records(records, cases, dataset_sha256, mode)
