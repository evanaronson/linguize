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


if __name__ == "__main__":
    unittest.main()
