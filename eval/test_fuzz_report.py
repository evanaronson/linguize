"""Reviews cannot silently follow different models, answers or engine behavior."""
import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from fuzz import generate, requests
from fuzz_report import complete_evidence, evidence_digest, matching_review, main


class CompleteEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.corpus = generate()
        self.replies = [{"case": case, "provider": "gemini", "model": "actual", "prompt_sha256": "prompt",
                         "corpus_sha256": "corpus", "verdict": {"status": "ok"}} for case in requests(self.corpus)]
        self.engines = [{"request_id": row["case"]["request_id"], "result": "reviewed",
                         "replay_input": copy.deepcopy(row)} for row in self.replies]

    def audit(self):
        return complete_evidence(self.replies, self.engines, self.corpus)

    def test_complete_named_and_auto_pairs_pass_in_any_order(self):
        self.engines.reverse()
        self.assertEqual(200, len(self.audit()))

    def test_missing_replay_binding_is_not_evidence_for_current_answers(self):
        self.engines[0].pop("replay_input")
        with self.assertRaisesRegex(ValueError, "Replay input is unbound"):
            self.audit()

    def test_explicit_legacy_mode_only_accepts_missing_binding_for_historical_reports(self):
        self.engines[0].pop("replay_input")
        self.assertEqual(200, len(complete_evidence(self.replies, self.engines, self.corpus, legacy_unbound=True)))
        for option in ("require_repairs", "repair_collection"):
            with self.subTest(option=option), self.assertRaisesRegex(ValueError, "Historical unbound replay"):
                complete_evidence(self.replies, self.engines, self.corpus, legacy_unbound=True, **{option: True})
        self.engines[1]["replay_input"]["model"] = "stale"
        with self.assertRaisesRegex(ValueError, "differs from current reply"):
            complete_evidence(self.replies, self.engines, self.corpus, legacy_unbound=True)

    def test_historical_report_labels_unbound_evidence_without_changing_source_rows(self):
        for engine in self.engines:
            engine.pop("replay_input")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, rows in (("replies.jsonl", self.replies), ("engine.jsonl", self.engines), ("corpus.jsonl", self.corpus)):
                (root / name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
            argv = ["fuzz_report.py", "--results", str(root), "--corpus", str(root / "corpus.jsonl"),
                    "--reviews", str(root / "absent.json"), "--legacy-unbound"]
            with patch("sys.argv", argv), contextlib.redirect_stdout(io.StringIO()):
                main()
            self.assertEqual({"legacy_unbound": 200}, json.loads((root / "summary.json").read_text())["replay_input_binding"])
            self.assertIn("Historical unbound replay", (root / "review.html").read_text())
            joined = [json.loads(line) for line in (root / "review.jsonl").read_text().split("\n") if line]
            self.assertTrue(all(row["replay_input_binding"] == "legacy_unbound" for row in joined))
            self.assertEqual(self.engines, [json.loads(line) for line in (root / "engine.jsonl").read_text().split("\n") if line])
            with patch("sys.argv", argv + ["--require-repairs"]), contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit):
                main()

    def test_changed_replay_inputs_need_a_new_replay(self):
        changes = {"provider": "openai", "model": "different", "prompt_sha256": "new-prompt",
                   "corpus_sha256": "new-corpus", "verdict": {"status": "unclear"},
                   "repair": {"provider": "gemini", "model": "actual", "attempts": 1,
                              "verdict": {"status": "ok", "meaning": "repaired"}}}
        for field, value in changes.items():
            with self.subTest(field=field):
                self.setUp()
                self.replies[0][field] = value
                with self.assertRaisesRegex(ValueError, "differs from current reply"):
                    self.audit()
                self.engines[0]["replay_input"] = copy.deepcopy(self.replies[0])
                self.assertEqual(200, len(self.audit()))

    def test_replay_binding_checks_recorded_case_and_repair(self):
        self.engines[0]["replay_input"]["case"]["text"] = "another replayed passage"
        with self.assertRaisesRegex(ValueError, "differs from current reply"):
            self.audit()
        self.setUp()
        self.replies[0]["repair"] = {"provider": "gemini", "model": "actual", "attempts": 1,
                                     "error": "TimeoutError"}
        self.engines[0]["replay_input"] = copy.deepcopy(self.replies[0])
        self.replies[0]["repair"]["error"] = "InterruptedBeforeResult"
        with self.assertRaisesRegex(ValueError, "differs from current reply"):
            self.audit()

    def test_equivalent_json_order_and_collection_timing_do_not_require_replay(self):
        self.engines[0]["replay_input"] = dict(reversed(list(self.replies[0].items())))
        self.replies[0].update(latency_seconds=42, attempts=3)
        self.assertEqual(200, len(self.audit()))

    def test_repair_resume_skips_only_already_recorded_repairs(self):
        self.replies[0]["repair"] = {"provider": "gemini", "model": "actual", "attempts": 1,
                                     "error": "InterruptedBeforeResult"}
        self.engines[0].pop("replay_input")
        self.assertEqual(200, len(complete_evidence(self.replies, self.engines, self.corpus, repair_collection=True)))
        with self.assertRaisesRegex(ValueError, "Replay input is unbound"):
            self.audit()
        self.engines[1].pop("replay_input")
        with self.assertRaisesRegex(ValueError, "Replay input is unbound"):
            complete_evidence(self.replies, self.engines, self.corpus, repair_collection=True)

    def test_final_live_gate_requires_recorded_retry_but_accepts_recorded_failure(self):
        for row in self.engines:
            row["retry_evidence"] = "not_needed"
        self.engines[0]["retry_evidence"] = "simulated"
        with self.assertRaisesRegex(ValueError, "actual repair evidence"):
            complete_evidence(self.replies, self.engines, self.corpus, require_repairs=True)
        self.replies[0].update(provider="gemini", model="actual")
        self.replies[0]["repair"] = {"provider": "gemini", "model": "actual", "attempts": 1, "error": "TimeoutError"}
        self.engines[0]["retry_evidence"] = "recorded"
        self.engines[0]["replay_input"] = copy.deepcopy(self.replies[0])
        self.assertEqual(200, len(complete_evidence(self.replies, self.engines, self.corpus, require_repairs=True)))
        self.replies[0]["repair"]["model"] = "different"
        with self.assertRaisesRegex(ValueError, "same-provider/model"):
            self.audit()

    def test_provider_errors_cannot_count_as_completed_checks(self):
        self.replies[0].pop("verdict")
        self.replies[0]["error"] = "timeout"
        with self.assertRaisesRegex(ValueError, "returned verdict"):
            self.audit()

    def test_failed_replays_cannot_count_as_completed_checks(self):
        for result in ("provider_error", "checker_error", None):
            with self.subTest(result=result):
                self.engines[0]["result"] = result
                with self.assertRaisesRegex(ValueError, "successful replay"):
                    self.audit()

    def test_duplicate_reply_or_replay_cannot_hide_a_missing_request(self):
        for field in ("replies", "engines"):
            with self.subTest(field=field):
                self.setUp()
                rows = getattr(self, field)
                rows[-1] = copy.deepcopy(rows[0])
                with self.assertRaisesRegex(ValueError, "Duplicate"):
                    self.audit()

    def test_missing_and_unexpected_identities_fail(self):
        for field in ("replies", "engines"):
            for replacement in (False, True):
                with self.subTest(field=field, replacement=replacement):
                    self.setUp()
                    rows = getattr(self, field)
                    if replacement:
                        identity = rows[-1]["case"] if field == "replies" else rows[-1]
                        identity["request_id"] = "unexpected:auto"
                    else:
                        rows.pop()
                    with self.assertRaisesRegex(ValueError, "every corpus request"):
                        self.audit()

    def test_correct_ids_cannot_disguise_repeated_inputs_or_wrong_modes(self):
        for field in ("text", "language"):
            with self.subTest(field=field):
                self.setUp()
                self.replies[0]["case"][field] = "different"
                with self.assertRaisesRegex(ValueError, "differs from corpus"):
                    self.audit()

    def test_incomplete_or_duplicated_corpus_fails(self):
        self.corpus.pop()
        with self.assertRaisesRegex(ValueError, "100 distinct"):
            self.audit()
        self.setUp()
        self.corpus[-1] = copy.deepcopy(self.corpus[0])
        with self.assertRaisesRegex(ValueError, "100 distinct"):
            self.audit()

    def test_unique_ids_do_not_make_repeated_passages_complete(self):
        self.corpus[-1]["text"] = self.corpus[0]["text"]
        with self.assertRaisesRegex(ValueError, "100 distinct"):
            self.audit()


class ReviewProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.reply = {"provider": "provider-a", "model": "any-model", "prompt_sha256": "prompt",
                      "corpus_sha256": "corpus", "verdict": {"corrected": "Bon dia."}}
        self.engine = {"edits": [{"accepted_text": "Bon dia."}]}
        self.note = {**{k: self.reply[k] for k in ("provider", "model", "prompt_sha256", "corpus_sha256")},
                     "verdict_sha256": evidence_digest(self.reply["verdict"]),
                     "engine_sha256": evidence_digest(self.engine), "review_status": "reviewed"}

    def test_exact_saved_answer_and_engine_keep_the_review(self):
        self.assertEqual("reviewed", matching_review(self.reply, self.engine, self.note)["review_status"])

    def test_a_repair_payload_needs_its_own_matching_review_hash(self):
        self.reply["repair"] = {"verdict": {"corrected": "Bona nit."}}
        self.assertEqual("not_reviewed_for_this_answer", matching_review(self.reply, self.engine, self.note)["review_status"])
        self.note["repair_sha256"] = evidence_digest(self.reply["repair"])
        self.assertEqual("reviewed", matching_review(self.reply, self.engine, self.note)["review_status"])
        self.reply["repair"] = {"error": "TimeoutError"}
        self.assertEqual("not_reviewed_for_this_answer", matching_review(self.reply, self.engine, self.note)["review_status"])

    def test_a_different_provider_model_prompt_or_corpus_needs_a_fresh_review(self):
        for field in ("provider", "model", "prompt_sha256", "corpus_sha256"):
            with self.subTest(field=field):
                reply = {**self.reply, field: "different"}
                self.assertEqual("not_reviewed_for_this_answer", matching_review(reply, self.engine, self.note)["review_status"])

    def test_a_new_answer_from_the_same_model_needs_a_fresh_review(self):
        reply = copy.deepcopy(self.reply)
        reply["verdict"]["corrected"] = "Bona nit."
        self.assertEqual("not_reviewed_for_this_answer", matching_review(reply, self.engine, self.note)["review_status"])

    def test_new_accept_behavior_needs_a_fresh_review_even_with_the_same_model_answer(self):
        engine = {"edits": [{"accepted_text": "Bon bon dia."}]}
        self.assertEqual("not_reviewed_for_this_answer", matching_review(self.reply, engine, self.note)["review_status"])


if __name__ == "__main__":
    unittest.main()
