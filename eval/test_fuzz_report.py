"""Reviews cannot silently follow different models, answers or engine behavior."""
import copy
import unittest
from fuzz_report import evidence_digest, matching_review


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
