"""Reviews cannot silently follow different models, answers or engine behavior."""
import copy
import unittest
from fuzz import generate, requests
from fuzz_report import complete_evidence, evidence_digest, matching_review


class CompleteEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.corpus = generate()
        self.replies = [{"case": case, "verdict": {"status": "ok"}} for case in requests(self.corpus)]
        self.engines = [{"request_id": row["case"]["request_id"], "result": "reviewed"} for row in self.replies]

    def audit(self):
        return complete_evidence(self.replies, self.engines, self.corpus)

    def test_complete_named_and_auto_pairs_pass_in_any_order(self):
        self.engines.reverse()
        self.assertEqual(200, len(self.audit()))

    def test_final_live_gate_requires_recorded_retry_but_accepts_recorded_failure(self):
        for row in self.engines:
            row["retry_evidence"] = "not_needed"
        self.engines[0]["retry_evidence"] = "simulated"
        with self.assertRaisesRegex(ValueError, "actual repair evidence"):
            complete_evidence(self.replies, self.engines, self.corpus, require_repairs=True)
        self.replies[0].update(provider="gemini", model="actual")
        self.replies[0]["repair"] = {"provider": "gemini", "model": "actual", "attempts": 1, "error": "TimeoutError"}
        self.engines[0]["retry_evidence"] = "recorded"
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
