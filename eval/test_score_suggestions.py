"""Offline contract/experiment checks. No provider requests or private fixtures."""
import contextlib
import copy
import csv
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import score_suggestions as experiment


def case():
    return {"id": "fixture", "original": "😀 Com estas?", "kind": "fix", "start": 6,
            "end": 11, "from": "estas", "to": "estàs", "context": {
                "targetLanguage": "ca", "nativeLanguage": "English", "punctuation": "moderate", "settled": []},
            "originalReason": "A model reason that must not anchor the assessor",
            "decision": "accepted", "reference": {"confidence": 3}}


def assessment(confidence=3, impact=1):
    return {"confidence": confidence, "impact": impact, "evidence": "An accent is required here."}


def result(c, answer=None, repeat=0):
    return {"id": c["id"], "caseHash": experiment.digest(experiment.assessable(c)),
            "datasetHash": experiment.digest([experiment.assessable(c)]),
            "rubricVersion": experiment.VERSION, "rubricHash": experiment.rubric_contract()[2],
            "provider": "fixture", "model": "fixture-model", "repeat": repeat, "assessment": answer}


def exported():
    return {"session": {"id": "s1", "schema": 5, "text": "😀 Com estas?",
                        "punctuation": "strict", "targetLanguage": "es",
                        "attempts": [{"targetLanguage": "ca", "punctuation": "moderate",
                                      "settled": [], "requestedLanguage": None},
                                     {"targetLanguage": "es", "punctuation": "strict",
                                      "settled": [{"about": "Later fact", "answer": "Do not leak"}]}]},
            "suggestions": [{"id": "g1", "attempt": 0, "kind": "fix", "start": 7, "end": 12,
                             "fromText": "estas", "toText": "estàs", "decision": "accepted", "why": "Accent"}]}


class AssessmentTest(unittest.TestCase):
    def test_history_uses_utf16_spans_and_own_attempt_context(self):
        row = exported()
        before = copy.deepcopy(row)
        c = experiment.history_cases([row])[0]
        self.assertEqual((6, 11), (c["start"], c["end"]))
        self.assertEqual("ca", c["context"]["targetLanguage"])
        self.assertEqual("moderate", c["context"]["punctuation"])
        self.assertEqual([], c["context"]["settled"])
        self.assertIsNone(c["context"]["requestedLanguage"])
        self.assertIsNone(c["context"]["nativeLanguage"])
        self.assertEqual(before, row)
        self.assertNotIn("decision", c)

    def test_legacy_context_only_falls_back_for_old_schema(self):
        row = exported()
        row["session"]["schema"] = 3
        row["session"]["nativeLanguage"] = "English"
        self.assertEqual("English", experiment.history_cases([row])[0]["context"]["nativeLanguage"])
        row["session"]["schema"] = 5
        self.assertIsNone(experiment.history_cases([row])[0]["context"]["nativeLanguage"])

    def test_unknown_kind_bad_attempt_bad_span_and_surrogate_boundary_fail(self):
        for key, value in [("kind", "future-kind"), ("attempt", -1), ("start", 1),
                           ("fromText", "wrong"), ("attempt", True)]:
            with self.subTest(key=key, value=value):
                row = exported()
                row["suggestions"][0][key] = value
                with self.assertRaises((ValueError, KeyError)):
                    experiment.history_cases([row])

    def test_zero_is_assessed_but_null_boolean_fractional_missing_extra_scores_fail(self):
        self.assertEqual(0, experiment.validate_answer(assessment(0, 0))["confidence"])
        invalid = [assessment(None), assessment(True), assessment(2.0), assessment(4),
                   assessment(2, -1), {"confidence": 2, "impact": 1},
                   dict(assessment(), alternative="other words"), dict(assessment(), evidence=" ")]
        for answer in invalid:
            with self.subTest(answer=answer), self.assertRaises(ValueError):
                experiment.validate_answer(answer)

    def test_assessor_gets_no_reason_decision_source_or_reference(self):
        message = experiment.assessable(case())
        self.assertEqual({"original", "kind", "start", "end", "from", "to", "context"}, set(message))
        self.assertNotIn("accepted", json.dumps(message))
        self.assertNotIn("anchor", json.dumps(message))

    def test_runner_keeps_failed_calls_null_and_continues_repeats(self):
        calls = []
        def check(model, key, system, schema, message):
            calls.append(json.loads(message))
            if len(calls) == 1:
                return assessment(True), .1
            return assessment(0, 0), .2
        with tempfile.TemporaryDirectory() as folder, \
                patch.dict(experiment.provider_eval.PROVIDERS, {"fake": (check, "fixture-model", "FIXTURE_KEY")}), \
                patch.dict(os.environ, {"FIXTURE_KEY": "fixture"}), contextlib.redirect_stdout(io.StringIO()):
            out = Path(folder) / "results.jsonl"
            status = experiment.score([case()], out, "fake", None, 3)
            rows = experiment.load_lines(out)
            self.assertEqual(1, status)
            self.assertIsNone(rows[0]["assessment"])
            self.assertEqual(0, rows[1]["assessment"]["confidence"])
            self.assertEqual(3, len(calls))
            self.assertNotIn("decision", calls[0])
            with self.assertRaises(FileExistsError):
                experiment.score([case()], out, "fake", None, 1)

    def test_without_key_no_output_and_no_provider_call(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            out = Path(folder) / "missing.jsonl"
            with self.assertRaisesRegex(ValueError, "Configure GEMINI_API_KEY"):
                experiment.score([case()], out, "gemini", None, 1)
            self.assertFalse(out.exists())

    def test_summary_never_claims_accuracy_without_references(self):
        c = case()
        rows = [result(c, assessment(), i) for i in range(3)]
        summary = experiment.summarize([c], rows, {})
        self.assertEqual(1, summary["stableRepeatedCases"])
        self.assertEqual(1, summary["assessedCases"])
        self.assertTrue(all(b["justifiedFraction"] is None for b in summary["confidenceBins"]))
        self.assertEqual(0, summary["referenceAgreement"]["impact"]["cases"])
        rows[1]["assessment"] = assessment(1, 3)
        self.assertEqual(0, experiment.summarize([c], rows, {})["stableRepeatedCases"])

    def test_repeated_calls_count_as_one_reviewed_case(self):
        c = case()
        rows = [result(c, assessment(), i) for i in range(3)]
        references = {c["id"]: {"justified": "no", "confidence": 1, "impact": 2}}
        summary = experiment.summarize([c], rows, references)
        self.assertEqual(1, summary["confidenceBins"][3]["reviewedCases"])
        self.assertEqual(0, summary["confidenceBins"][3]["justifiedFraction"])
        self.assertEqual(1, summary["referenceAgreement"]["confidence"]["matrixReferenceRowsAssessorColumns"][1][3])

    def test_mixed_stale_duplicate_or_invalid_repeat_results_cannot_be_compared(self):
        c = case()
        for field, value in [("caseHash", "wrong"), ("datasetHash", "wrong"),
                             ("rubricHash", "wrong"), ("repeat", True)]:
            row = result(c, assessment())
            row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                experiment.summarize([c], [row], {})
        row = result(c, assessment())
        with self.assertRaises(ValueError):
            experiment.summarize([c], [row, row], {})
        other = result(c, assessment(), 1)
        other["model"] = "another-model"
        with self.assertRaises(ValueError):
            experiment.summarize([c], [row, other], {})

    def test_reports_escape_messages_and_blind_scores_and_leave_references_blank(self):
        c = case()
        c["to"] = '<script>alert("x")</script>'
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "report"
            experiment.report([c], [result(c, assessment(0, 0))], {}, out)
            page = (out / "review.html").read_text()
            blind = (out / "blind.html").read_text()
            self.assertIn("confidence 0/3", page)
            self.assertNotIn("confidence 0/3", blind)
            self.assertNotIn("<script>", page)
            self.assertIn("&lt;script&gt;", page)
            self.assertNotIn("anchor the assessor", page)
            references = experiment.read_references(out / "references.csv", [c])
            self.assertIsNone(references[c["id"]]["confidence"])
            self.assertIsNone(references[c["id"]]["impact"])
            with self.assertRaises(FileExistsError):
                experiment.report([c], [], {}, out)

    def test_reference_needs_matching_input_and_identified_reviewer(self):
        c = case()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "references.csv"
            fields = ["id", "caseHash", "confidence", "impact", "justified", "reviewer"]
            def write(**changes):
                row = {"id": c["id"], "caseHash": experiment.digest(experiment.assessable(c)),
                       "confidence": "0", "impact": "0", "justified": "no", "reviewer": "reviewer-1"}
                row.update(changes)
                with path.open("w", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=fields)
                    writer.writeheader(); writer.writerow(row)
            write()
            self.assertEqual(0, experiment.read_references(path, [c])[c["id"]]["impact"])
            write(reviewer="")
            with self.assertRaises(ValueError):
                experiment.read_references(path, [c])
            write(caseHash="old")
            with self.assertRaises(ValueError):
                experiment.read_references(path, [c])

    def test_cases_reject_csv_formula_ids_and_no_op_adjustments(self):
        c = case()
        c["id"] = "=FORMULA()"
        with self.assertRaises(ValueError):
            experiment.validate_case(c)
        c = case()
        c["to"] = c["from"]
        with self.assertRaises(ValueError):
            experiment.validate_case(c)


if __name__ == "__main__":
    unittest.main()
