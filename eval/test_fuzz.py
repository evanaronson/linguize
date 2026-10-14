"""Corpus structure and mutation replay tests; never call a provider."""
import collections
import importlib.util
from pathlib import Path
import unittest

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
            argv = ["--live", "--workers", "1", "--interval", "0", "--output", str(corpus),
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
            argv = ["--live", "--model", "primary-fixture", "--fallback", "openai:secondary-fixture",
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
                self.assertEqual(0, fuzz.main(argv + ["--retry-errors"]))
                self.assertEqual(400, len(calls))
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
            self.assertEqual(0, fuzz.main(["--live", "--tries", "1", "--interval", "0",
                                         "--fallback", "gemini:secondary-fixture",
                                         "--output", str(Path(folder) / "corpus.jsonl"),
                                         "--replies", str(Path(folder) / "replies.jsonl")]))
        self.assertEqual(["primary-fixture"] * 200, calls)


if __name__ == "__main__":
    unittest.main()

