"""Corpus structure and mutation replay tests; never call a provider."""
import collections
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("fuzz_runner", Path(__file__).with_name("fuzz.py"))
fuzz = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fuzz)


class FuzzCorpusTest(unittest.TestCase):
    def test_runner_resolves_the_shipped_assets_before_any_live_call(self):
        runner = fuzz.load_runner()
        self.assertEqual(fuzz.ROOT, runner.ROOT)
        self.assertTrue((runner.ASSETS / "check_prompt.md").is_file())
        self.assertTrue((runner.ASSETS / "check_schema.json").is_file())

    def test_exactly_one_hundred_distinct_two_or_three_sentence_passages(self):
        records = fuzz.generate()
        self.assertEqual(100, len(records))
        self.assertEqual(100, len({r["original"] for r in records}))
        self.assertEqual({"Catalan": 50, "Spanish": 50}, collections.Counter(r["source_language"] for r in records))
        self.assertTrue(all(len(r["original_sentences"]) in (2, 3) for r in records))
        self.assertTrue(all(r["original"] != r["text"] for r in records))

    def test_seed_is_repeatable_and_changes_the_corruptions(self):
        self.assertEqual(fuzz.generate(1), fuzz.generate(1))
        self.assertNotEqual([r["text"] for r in fuzz.generate(1)], [r["text"] for r in fuzz.generate(2)])

    def test_every_mutation_family_and_each_language_mode_are_exercised(self):
        records = fuzz.generate()
        self.assertEqual(set(fuzz.OPERATIONS), {m["operation"] for r in records for m in r["mutations"]})
        requests = list(fuzz.requests(records))
        self.assertEqual(200, len(requests))
        self.assertEqual(200, len({r["request_id"] for r in requests}))
        self.assertEqual({"Catalan": 50, "Spanish": 50, "auto": 100},
                         collections.Counter(r["language"] for r in requests))
        for original in records:
            runs = [r for r in requests if r["id"] == original["id"]]
            self.assertEqual({original["source_language"], "auto"}, {r["language"] for r in runs})
            self.assertTrue(all(r["text"] == original["text"] for r in runs))

    def test_unicode_line_separator_stays_inside_a_jsonl_record(self):
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cases.jsonl"
            records = fuzz.generate()
            path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records))
            rows = [json.loads(line) for line in path.read_text().split("\n") if line]
            self.assertEqual(records, rows)
            self.assertTrue(any("\u2028" in r["text"] for r in rows))

    def test_resuming_retries_only_the_failed_request_and_keeps_exactly_two_hundred_records(self):
        import contextlib
        import io
        import json
        import os
        import tempfile
        from unittest.mock import patch
        runner = fuzz.load_runner()
        calls = []

        def answer(*args):
            calls.append(args[-1])
            if len(calls) <= 3:
                raise TimeoutError("fixture timeout")
            return {"status": "ok"}, 0.01

        with tempfile.TemporaryDirectory() as folder:
            corpus = Path(folder) / "corpus.jsonl"
            replies = Path(folder) / "replies.jsonl"
            argv = ["--live", "--mode", "base", "--workers", "1", "--interval", "0", "--output", str(corpus),
                    "--replies", str(replies)]
            with patch.object(fuzz, "load_runner", return_value=runner), \
                    patch.dict(runner.PROVIDERS, {"gemini": (answer, "fixture-model", "FUZZ_FIXTURE_KEY")}), \
                    patch.dict(os.environ, {"FUZZ_FIXTURE_KEY": "fixture"}), \
                    patch.object(fuzz.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, fuzz.main(argv))
                before = len(calls)
                self.assertEqual(0, fuzz.main(argv + ["--retry-errors"]))
                self.assertEqual(1, len(calls) - before)
            saved = [json.loads(line) for line in replies.read_text().split("\n") if line]
            self.assertEqual(200, len(saved))
            self.assertEqual(200, len({r["case"]["request_id"] for r in saved}))
            self.assertTrue(all("verdict" in r for r in saved))

    def test_fallback_is_provider_agnostic_keeps_failures_and_resumes_without_repeating_answers(self):
        import contextlib
        import io
        import json
        import os
        import tempfile
        from unittest.mock import patch
        runner = fuzz.load_runner()
        calls = []

        def unavailable(model, *args):
            calls.append(model)
            raise fuzz.urllib.error.HTTPError("https://fixture.invalid", 429, "rate limited", {}, None)

        def answer(model, *args):
            calls.append(model)
            return {"status": "ok", "has_errors": False}, 0.01

        with tempfile.TemporaryDirectory() as folder:
            replies = Path(folder) / "replies.jsonl"
            argv = ["--live", "--mode", "base", "--model", "primary-fixture", "--fallback", "openai:secondary-fixture",
                    "--tries", "3", "--workers", "1", "--interval", "0",
                    "--output", str(Path(folder) / "corpus.jsonl"), "--replies", str(replies)]
            providers = {"gemini": (unavailable, "primary-fixture", "FUZZ_A_KEY"),
                         "openai": (answer, "secondary-fixture", "FUZZ_B_KEY")}
            output = io.StringIO()
            with patch.object(fuzz, "load_runner", return_value=runner), patch.dict(runner.PROVIDERS, providers), \
                    patch.dict(os.environ, {"FUZZ_A_KEY": "private-a-fixture", "FUZZ_B_KEY": "private-b-fixture"}), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(0, fuzz.main(argv))
                self.assertEqual(400, len(calls))
                # Existing archives predate mode metadata. Explicit base resumes
                # them without inventing app identity or rewriting good answers.
                legacy = [json.loads(line) for line in replies.read_text().split("\n") if line]
                for row in legacy:
                    self.assertEqual("base", row.pop("mode"))
                replies.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in legacy))
                historical_bytes = replies.read_bytes()
                self.assertEqual(0, fuzz.main(argv + ["--retry-errors"]))
                self.assertEqual(400, len(calls))
                self.assertEqual(historical_bytes, replies.read_bytes())
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    fuzz.main(argv + ["--model", "a-different-experiment"])
                self.assertEqual(400, len(calls))
            rows = [json.loads(line) for line in replies.read_text().split("\n") if line]
            self.assertEqual(200, len(rows))
            self.assertTrue(all(r["provider"] == "openai" and r["model"] == "secondary-fixture" for r in rows))
            self.assertTrue(all(r["requested_model"] == "primary-fixture" and
                                r["failures"][0]["model"] == "primary-fixture" for r in rows))
            self.assertNotIn("private-a-fixture", replies.read_text() + output.getvalue())
            self.assertNotIn("private-b-fixture", replies.read_text() + output.getvalue())

    def test_an_unfavorable_judgment_does_not_trigger_fallback(self):
        import contextlib
        import io
        import os
        import tempfile
        from unittest.mock import patch
        runner = fuzz.load_runner()
        calls = []

        def answer(model, *args):
            calls.append(model)
            return {"status": "unclear", "has_errors": False}, 0.01

        with tempfile.TemporaryDirectory() as folder, \
                patch.object(fuzz, "load_runner", return_value=runner), \
                patch.dict(runner.PROVIDERS, {"gemini": (answer, "primary-fixture", "FUZZ_FIXTURE_KEY")}), \
                patch.dict(os.environ, {"FUZZ_FIXTURE_KEY": "fixture"}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, fuzz.main(["--live", "--mode", "base", "--tries", "1", "--interval", "0",
                                         "--fallback", "gemini:secondary-fixture",
                                         "--output", str(Path(folder) / "corpus.jsonl"),
                                         "--replies", str(Path(folder) / "replies.jsonl")]))
        self.assertEqual(["primary-fixture"] * 200, calls)


    def test_repairs_use_actual_responder_once_preserve_first_answer_and_resume(self):
        import contextlib
        import io
        import json
        import os
        import tempfile
        from unittest.mock import patch
        runner = fuzz.load_runner()
        repair_calls = []
        with tempfile.TemporaryDirectory() as folder:
            replies = Path(folder) / "replies.jsonl"
            engine = Path(folder) / "engine.jsonl"
            argv = ["--live", "--mode", "base", "--workers", "1", "--interval", "0", "--output", str(Path(folder) / "corpus.jsonl"),
                    "--replies", str(replies)]

            def first(*args):
                self.assertNotIn("\nRepair: true\n", args[-1])
                return {"status": "ok", "meaning": "first answer"}, 0.01

            def repair(model, key, system, schema, message):
                repair_calls.append((model, key, message))
                # The attempt is reserved before the provider sees the request.
                saved = [json.loads(line) for line in replies.read_text().split("\n") if line]
                self.assertTrue(any(row.get("repair", {}).get("error") == "InterruptedBeforeResult" for row in saved))
                self.assertIn("\nRepair: true\nText: ", message)
                if len(repair_calls) == 2:
                    raise TimeoutError("do not retry this semantic call")
                return {"status": "ok", "meaning": "repair answer"}, 0.01

            with patch.object(fuzz, "load_runner", return_value=runner), \
                    patch.dict(runner.PROVIDERS, {"gemini": (first, "requested-model", "FIRST_KEY"),
                                                 "openai": (repair, "unused-default", "ACTUAL_KEY")}), \
                    patch.dict(os.environ, {"FIRST_KEY": "first", "ACTUAL_KEY": "actual"}), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, fuzz.main(argv + ["--model", "original-requested-model"]))
                rows = [json.loads(line) for line in replies.read_text().split("\n") if line]
                for row in rows[:3]:
                    row.update(provider="openai", model="actual-responder")
                # A previously interrupted repair is evidence, not permission to repeat it.
                rows[2]["repair"] = {"provider": "openai", "model": "actual-responder", "attempts": 1,
                                     "error": "InterruptedBeforeResult"}
                replies.write_text("".join(json.dumps(row) + "\n" for row in rows))
                engine.write_text("".join(json.dumps({"request_id": row["case"]["request_id"], "result": "reviewed",
                                                      "replay_input": row,
                                                      "withheld": ["fix"] if i < 3 else []}) + "\n"
                                          for i, row in enumerate(rows)))
                bound_replay = engine.read_text()
                stale = [json.loads(line) for line in bound_replay.split("\n") if line]
                stale[0]["replay_input"]["verdict"] = {"status": "unclear", "meaning": "old answer"}
                engine.write_text("".join(json.dumps(row) + "\n" for row in stale))
                with self.assertRaisesRegex(ValueError, "differs from current reply"):
                    fuzz.main(argv + ["--repair-from", str(engine)])
                self.assertEqual([], repair_calls)
                self.assertEqual(rows, [json.loads(line) for line in replies.read_text().split("\n") if line])
                engine.write_text(bound_replay)
                self.assertEqual(0, fuzz.main(argv + ["--repair-from", str(engine)]))
                self.assertEqual(0, fuzz.main(argv + ["--repair-from", str(engine), "--retry-errors"]))
                saved = [json.loads(line) for line in replies.read_text().split("\n") if line]
            self.assertEqual(2, len(repair_calls))
            self.assertTrue(all(call[:2] == ("actual-responder", "actual") for call in repair_calls))
            self.assertEqual([row["verdict"] for row in rows], [row["verdict"] for row in saved])
            self.assertEqual("repair answer", saved[0]["repair"]["verdict"]["meaning"])
            self.assertEqual("TimeoutError", saved[1]["repair"]["error"])
            self.assertEqual("InterruptedBeforeResult", saved[2]["repair"]["error"])
            self.assertTrue(all("repair" not in row for row in saved[3:]))


class FuzzPreparedRequestTest(unittest.TestCase):
    def setUp(self):
        self.runner = fuzz.load_runner()
        self.cases = list(fuzz.requests(fuzz.generate()))
        self.prepared = []
        for index, case in enumerate(self.cases):
            requests = {}
            for phase in ("initial", "repair"):
                requests[phase] = {
                    "generation": {"system": "Kotlin-composed gaps and tone fixture\n", "user": phase + "\n" + case["text"],
                                   "schema": {"type": "object", "properties": {"landing": {"type": "string"}}},
                                   "name": "check", "max_tokens": 8192},
                    "compatibility_hash": "legacy-cache-hash-g-t", "contract_sha256": fuzz.digest("contract"),
                    "request_sha256": fuzz.digest(case["request_id"] + ":" + phase),
                    "parser": {"policy": "base-fresh-v1", "prompt_hash": "base-parser-hash"},
                }
            self.prepared.append({"version": 1, "mode": "app", "case_index": index, "case": case,
                                  "dataset_sha256": "bridge-validates-input-bytes", **requests})
        self.by_id = {row["case"]["request_id"]: row for row in self.prepared}

    def arguments(self, folder):
        return ["--live", "--workers", "1", "--interval", "0", "--tries", "1",
                "--output", str(Path(folder) / "corpus.jsonl"), "--replies", str(Path(folder) / "replies.jsonl")]

    def test_default_app_sends_exported_initial_and_repair_once_and_preserves_provenance(self):
        calls = []
        with tempfile.TemporaryDirectory() as folder:
            replies = Path(folder) / "replies.jsonl"
            engine = Path(folder) / "engine.jsonl"

            def answer(provider, model, key, generation):
                calls.append((provider, model, copy.deepcopy(generation)))
                if generation["user"].startswith("repair\n"):
                    saved = [json.loads(line) for line in replies.read_text().split("\n") if line]
                    self.assertEqual("InterruptedBeforeResult", saved[0]["repair"]["error"])
                return {"status": "ok", "meaning": generation["user"].split("\n")[0]}, 0.01

            with patch.object(fuzz, "load_runner", return_value=self.runner), \
                    patch.object(fuzz, "prepare_requests", return_value=self.prepared) as prepare, \
                    patch.object(self.runner, "check_prepared", side_effect=answer), \
                    patch.object(self.runner, "user_message", side_effect=AssertionError("app must not format in Python")), \
                    patch.dict(os.environ, {"GEMINI_API_KEY": "fixture"}), contextlib.redirect_stdout(io.StringIO()):
                argv = self.arguments(folder)
                self.assertEqual(0, fuzz.main(argv))
                prepare.assert_called_once_with(self.cases)
                self.assertEqual([row["initial"]["generation"] for row in self.prepared], [call[2] for call in calls])
                first = [json.loads(line) for line in replies.read_text().split("\n") if line]
                for row in first:
                    self.assertEqual(fuzz.preparation_identity(self.by_id[row["case"]["request_id"]]),
                                     {field: row[field] for field in (*fuzz.PREPARATION_FIELDS, "prompt_sha256")})
                engine.write_text("".join(json.dumps({"request_id": row["case"]["request_id"], "result": "reviewed",
                                                      "replay_input": row, "withheld": ["fix"] if i == 0 else []}) + "\n"
                                          for i, row in enumerate(first)))
                self.assertEqual(0, fuzz.main(argv + ["--repair-from", str(engine)]))
                self.assertEqual(201, len(calls))
                self.assertEqual(self.by_id[first[0]["case"]["request_id"]]["repair"]["generation"], calls[-1][2])
                self.assertEqual(0, fuzz.main(argv + ["--repair-from", str(engine), "--retry-errors"]))
                self.assertEqual(201, len(calls))
                saved = [json.loads(line) for line in replies.read_text().split("\n") if line]
                self.assertEqual([row["verdict"] for row in first], [row["verdict"] for row in saved])
                self.assertEqual("repair", saved[0]["repair"]["verdict"]["meaning"])

    def test_changed_app_identity_fails_before_calls_or_repair_reservation(self):
        calls = []
        with tempfile.TemporaryDirectory() as folder, \
                patch.object(fuzz, "load_runner", return_value=self.runner), \
                patch.object(fuzz, "prepare_requests", return_value=self.prepared), \
                patch.object(self.runner, "check_prepared", side_effect=lambda *args: (calls.append(args) or {"status": "ok"}, 0.01)), \
                patch.dict(os.environ, {"GEMINI_API_KEY": "fixture"}), contextlib.redirect_stdout(io.StringIO()):
            argv = self.arguments(folder)
            replies = Path(folder) / "replies.jsonl"
            engine = Path(folder) / "engine.jsonl"
            self.assertEqual(0, fuzz.main(argv))
            rows = [json.loads(line) for line in replies.read_text().split("\n") if line]
            engine.write_text("".join(json.dumps({"request_id": row["case"]["request_id"], "result": "reviewed",
                                                  "replay_input": row, "withheld": ["fix"]}) + "\n" for row in rows))
            changes = {"mode": "base", "preparation_version": 2, "contract_sha256": "other-contract",
                       "request_sha256": "other-initial", "repair_contract_sha256": "other-repair-contract",
                       "repair_request_sha256": "other-repair", "parser": {"policy": "legacy", "prompt_hash": "base-parser-hash"},
                       "prompt_sha256": "old-prompt", "corpus_sha256": "old-corpus",
                       "case": {**rows[0]["case"], "text": "different"}}
            for field, value in changes.items():
                for repair in (False, True):
                    with self.subTest(field=field, repair=repair):
                        changed = copy.deepcopy(rows)
                        changed[0][field] = value
                        replies.write_text("".join(json.dumps(row) + "\n" for row in changed))
                        before = replies.read_bytes()
                        extra = ["--repair-from", str(engine)] if repair else []
                        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                            fuzz.main(argv + extra)
                        self.assertEqual(200, len(calls))
                        self.assertEqual(before, replies.read_bytes())
            rows[0]["preparation_version"] = True
            replies.write_text("".join(json.dumps(row) + "\n" for row in rows))
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                fuzz.main(argv)
            self.assertEqual(200, len(calls))
            # A historical row with no preparation metadata is never inferred to
            # have used the app, even when every old hash happens to match.
            for row in rows:
                for field in fuzz.PREPARATION_FIELDS:
                    row.pop(field)
            replies.write_text("".join(json.dumps(row) + "\n" for row in rows))
            before = replies.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                fuzz.main(argv)
            self.assertEqual(200, len(calls))
            self.assertEqual(before, replies.read_bytes())


if __name__ == "__main__":
    unittest.main()

