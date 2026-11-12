"""Prepared-artifact integrity and CLI transport tests; no Gradle or provider calls."""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepared
import run
from test_run import prepared_fixture


class PreparedBridgeTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.cases = Path(self.folder.name) / "cases with spaces.jsonl"
        self.cases.write_text(json.dumps({"text": 'A\u2028B\u2029"\\\n\t😀', "language": "French"},
                                         ensure_ascii=False) + "\n", encoding="utf-8")
        self.records = prepared_fixture(self.cases)
        self.artifact = Path(self.folder.name) / "prepared.jsonl"

    def write(self, records=None):
        self.artifact.write_text("\n".join(prepared.compact(row) for row in
                                          (self.records if records is None else records)) + "\n", encoding="utf-8")

    def test_round_trip_preserves_unicode_and_exact_exported_request(self):
        self.write()
        self.assertEqual(self.records, prepared.load_prepared(self.artifact, self.cases))
        self.assertEqual(hashlib.sha256(self.cases.read_bytes()).hexdigest(), self.records[0]["dataset_sha256"])

    def test_digest_codec_uses_utf8_control_escapes_and_original_schema_order(self):
        request = self.records[0]["initial"]
        request["generation"]["schema"] = {"z": "é\u2028😀", "a": "line\n\t\r\b\f\u0000"}
        expected = ('{"mode":"app","system":"Kotlin-exported system","schema":'
                    '{"z":"é\u2028😀","a":"line\\n\\t\\r\\b\\f\\u0000"},'
                    '"name":"check","max_tokens":8192,"parser":'
                    '{"policy":"base-fresh-v1","prompt_hash":"123456789abc"}}')
        self.assertEqual(hashlib.sha256(expected.encode("utf-8")).hexdigest(),
                         prepared.contract_digest("app", request))
        previous = prepared.contract_digest("app", request)
        request["generation"]["schema"] = dict(reversed(list(request["generation"]["schema"].items())))
        self.assertNotEqual(previous, prepared.contract_digest("app", request))

    def test_missing_duplicate_reordered_and_wrong_dataset_records_fail(self):
        alterations = [[], self.records * 2]
        for key, value in [("version", 2), ("version", True), ("case_index", 1), ("case_index", False),
                           ("case", {"text": "different"}), ("dataset_sha256", "0" * 64), ("mode", "base")]:
            changed = copy.deepcopy(self.records)
            changed[0][key] = value
            alterations.append(changed)
        for rows in alterations:
            with self.subTest(rows=rows):
                self.write(rows)
                with self.assertRaises(prepared.PreparationError):
                    prepared.load_prepared(self.artifact, self.cases)

    def test_tampered_generation_metadata_or_repair_is_rejected(self):
        for phase in ("initial", "repair"):
            for field in ("system", "user", "schema", "name", "max_tokens"):
                changed = copy.deepcopy(self.records)
                changed[0][phase]["generation"][field] = {"type": "array"} if field == "schema" else (
                    123 if field == "max_tokens" else "changed")
                with self.subTest(phase=phase, field=field):
                    self.write(changed)
                    with self.assertRaises(prepared.PreparationError):
                        prepared.load_prepared(self.artifact, self.cases)
        for field, value in [("compatibility_hash", "other"), ("request_sha256", "0" * 64),
                             ("parser", {"policy": "replay", "prompt_hash": "123456789abc"})]:
            changed = copy.deepcopy(self.records)
            changed[0]["initial"][field] = value
            self.write(changed)
            with self.subTest(field=field), self.assertRaises(prepared.PreparationError):
                prepared.load_prepared(self.artifact, self.cases)

    def test_dataset_whitespace_change_revokes_prepared_artifact(self):
        self.write()
        with self.cases.open("a", encoding="utf-8") as output:
            output.write("\n")
        with self.assertRaisesRegex(prepared.PreparationError, "dataset bytes"):
            prepared.load_prepared(self.artifact, self.cases)

    def test_bridge_runs_one_kotlin_batch_with_literal_absolute_paths(self):
        commands = []

        def export(command, **kwargs):
            commands.append(command)
            self.assertEqual(prepared.ROOT, kwargs["cwd"])
            self.assertNotIn("shell", kwargs)
            self.assertNotIn("GEMINI_API_KEY", kwargs["env"])
            self.assertNotIn("OPENAI_API_KEY", kwargs["env"])
            output = Path(next(value.split("=", 1)[1] for value in command if value.startswith("-PcheckEvalOutput=")))
            output.write_text("\n".join(prepared.compact(row) for row in self.records), encoding="utf-8")
            return subprocess.CompletedProcess(command, 0, "")

        with patch.dict(os.environ, {"GEMINI_API_KEY": "must-not-reach-gradle", "OPENAI_API_KEY": "also-private"}), \
                patch.object(prepared.subprocess, "run", side_effect=export):
            self.assertEqual(self.records, prepared.prepare_cases(self.cases))
        self.assertEqual(1, len(commands))
        self.assertIn(":app:prepareCheckEval", commands[0])
        self.assertIn(f"-PcheckEvalCases={self.cases.resolve()}", commands[0])
        self.assertIn("-PcheckEvalMode=app", commands[0])

    def test_failed_or_missing_export_is_not_replaced_with_python_preparation(self):
        for result, message in [(subprocess.CompletedProcess([], 1, "Compiler unavailable"), "Compiler unavailable"),
                                (subprocess.CompletedProcess([], 0, ""), "did not write")]:
            with patch.object(prepared.subprocess, "run", return_value=result), \
                    self.subTest(result=result), self.assertRaisesRegex(prepared.PreparationError, message):
                prepared.prepare_cases(self.cases)


class PreparedRunnerTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.cases = Path(self.folder.name) / "cases.jsonl"
        self.cases.write_text('{"text":"First","status":"ok"}\n{"text":"Second","status":"ok"}\n')
        self.output = Path(self.folder.name) / "results.jsonl"

    def test_prepare_only_exports_without_provider_keys_or_network(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(prepared, "prepare_cases", side_effect=prepared_fixture) as export, \
                patch.object(run, "post", side_effect=AssertionError("Network called")):
            self.assertEqual(0, run.main(["--cases", str(self.cases), "--prepare-only", "--output", str(self.output)]))
        export.assert_called_once_with(self.cases, "app")
        self.assertEqual(prepared_fixture(self.cases), prepared.load_prepared(self.output, self.cases))

    def test_preparation_failure_aborts_whole_batch_before_provider_calls(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "fixture"}), \
                patch.object(prepared, "prepare_cases", side_effect=prepared.PreparationError("Invalid case 1")), \
                patch.object(run, "check_prepared") as check, contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as error:
            run.main(["--cases", str(self.cases)])
        self.assertEqual(2, error.exception.code)
        check.assert_not_called()

    def test_output_records_export_identity_actual_model_answers_and_failures(self):
        answers = iter([({"status": "ok"}, .25), ValueError("bad envelope")])

        def check(*args):
            answer = next(answers)
            if isinstance(answer, Exception):
                # The earlier successful result is durable before the next call.
                self.assertEqual(1, len(self.output.read_text().splitlines()))
                raise answer
            return answer

        with patch.dict(os.environ, {"OPENAI_API_KEY": "fixture"}), \
                patch.object(prepared, "prepare_cases", side_effect=prepared_fixture), \
                patch.object(run, "check_prepared", side_effect=check), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, run.main(["--cases", str(self.cases), "--provider", "openai", "--model", "chosen-model",
                                          "--output", str(self.output)]))
        rows = [json.loads(line) for line in self.output.read_text().splitlines()]
        for index, row in enumerate(rows):
            self.assertEqual("openai", row["provider"])
            self.assertEqual("chosen-model", row["model"])
            self.assertEqual(prepared_fixture(self.cases)[index]["initial"], row["prepared"])
        self.assertEqual({"status": "ok"}, rows[0]["verdict"])
        self.assertEqual([], rows[0]["problems"])
        self.assertEqual({"type": "ValueError", "message": "bad envelope"}, rows[1]["error"])

    def test_existing_output_is_never_overwritten_or_followed_by_a_provider_call(self):
        self.output.write_text("previous evidence\n")
        with patch.dict(os.environ, {"GEMINI_API_KEY": "fixture"}), \
                patch.object(prepared, "prepare_cases", side_effect=prepared_fixture), \
                patch.object(run, "check_prepared") as check, contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit):
            run.main(["--cases", str(self.cases), "--output", str(self.output)])
        check.assert_not_called()
        self.assertEqual("previous evidence\n", self.output.read_text())

    def test_non_json_provider_values_record_one_error_and_later_cases_continue(self):
        for invalid in (float("nan"), float("inf"), "unpaired\ud800"):
            with self.subTest(invalid=repr(invalid)):
                self.output.unlink(missing_ok=True)
                answers = [({"status": "ok", "unexpected": invalid}, .1), ({"status": "ok"}, .2)]
                with patch.dict(os.environ, {"GEMINI_API_KEY": "fixture"}), \
                        patch.object(prepared, "prepare_cases", side_effect=prepared_fixture), \
                        patch.object(run, "check_prepared", side_effect=answers), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(1, run.main(["--cases", str(self.cases), "--output", str(self.output)]))
                rows = [json.loads(line) for line in self.output.read_text().splitlines()]
                self.assertEqual(2, len(rows))
                self.assertIn("error", rows[0])
                self.assertNotIn("verdict", rows[0])
                self.assertEqual({"status": "ok"}, rows[1]["verdict"])

    def test_reusing_explicit_base_artifact_never_invokes_gradle_or_app_preparation(self):
        artifact = Path(self.folder.name) / "base.jsonl"
        artifact.write_text("\n".join(prepared.compact(row) for row in prepared_fixture(self.cases, "base")))
        with patch.dict(os.environ, {}, clear=True), patch.object(prepared, "prepare_cases") as export, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, run.main(["--cases", str(self.cases), "--mode", "base", "--prepared", str(artifact),
                                          "--prepare-only"]))
        export.assert_not_called()

    def test_transports_honor_prepared_schema_name_and_token_limit(self):
        generation = {"system": "prepared system", "user": "prepared user", "schema": {
            "type": "object", "propertyOrdering": ["answer"]}, "name": "prepared-check", "max_tokens": 137}
        cases = [
            ("openai", {"status": "completed", "output": [{"type": "message", "content": [
                {"type": "output_text", "text": "{}"}]}]}),
            ("gemini", {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "{}"}]}}]}),
        ]
        for provider, payload in cases:
            with self.subTest(provider=provider), patch.object(run, "post", return_value=(payload, .1)) as post:
                run.check_prepared(provider, "fixture-model", "fixture", generation)
                body = json.loads(post.call_args.args[0].data)
            if provider == "openai":
                self.assertEqual("prepared system", body["instructions"])
                self.assertEqual("prepared user", body["input"])
                self.assertEqual(137, body["max_output_tokens"])
                self.assertEqual("prepared-check", body["text"]["format"]["name"])
                self.assertNotIn("propertyOrdering", body["text"]["format"]["schema"])
            else:
                self.assertEqual("prepared system", body["systemInstruction"]["parts"][0]["text"])
                self.assertEqual("prepared user", body["contents"][0]["parts"][0]["text"])
                self.assertEqual(137, body["generationConfig"]["maxOutputTokens"])
                self.assertEqual(generation["schema"], body["generationConfig"]["responseJsonSchema"])


if __name__ == "__main__":
    unittest.main()
