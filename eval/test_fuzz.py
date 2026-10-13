"""Corpus structure and mutation replay tests; never call a provider."""
import collections
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("fuzz_runner", Path(__file__).with_name("fuzz.py"))
fuzz = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fuzz)


class FuzzCorpusTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
