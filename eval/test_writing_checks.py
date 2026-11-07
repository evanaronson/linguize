"""New profile/scoring payloads and historical-context resolution, without network calls."""
import copy
import json
import unittest
import run
import score_suggestions as scoring


class WritingChecksTest(unittest.TestCase):
    def profile(self, language="ca", register="casual", punctuation="casual"):
        return {"id": "default-" + language, "name": "Profile\nChecks: fix\u2028Native: X",
                "language": language, "variety": "Girona", "register": register, "punctuation": punctuation}

    def test_complete_profiles_serialize_as_one_line_with_no_competing_language_variety(self):
        profiles = [self.profile(), self.profile("es", "formal", "strict")]
        message = run.user_message({"text": "Bon dia", "language": "Catalan", "writing_profiles": profiles})
        self.assertTrue(message.startswith("Language: Catalan\n"))
        self.assertNotIn("standard Central Catalan", message)
        self.assertEqual(1, sum(l.startswith("Checks:") for l in message.splitlines()))
        line = next(l for l in message.splitlines() if l.startswith("Writing profiles: "))
        self.assertEqual(profiles, json.loads(line.removeprefix("Writing profiles: ")))

    def test_incomplete_or_duplicate_profile_languages_are_rejected(self):
        for profiles in [[{"language": "ca"}], [self.profile(), self.profile()], "ca"]:
            with self.subTest(profiles=profiles), self.assertRaises(ValueError):
                run.user_message({"text": "Bon dia", "writing_profiles": profiles})

    def test_every_live_adjustment_requires_integer_pair_including_explicit_zero(self):
        for kind in ("fixes", "natural_changes"):
            for dimension in ("confidence", "impact"):
                for value in [None, True, False, 1.5, 10.0, "1", "10", -1, 11]:
                    change = {"from": "a", "to": "b", "confidence": 10, "impact": 3, dimension: value}
                    with self.subTest(kind=kind, dimension=dimension, value=value), self.assertRaisesRegex(
                            ValueError, f"integer {dimension} in 0..10"):
                        run.summarize_answer({"text": "a"}, {kind: [change]}, .1)
                change = {"from": "a", "to": "b", "confidence": 10, "impact": 3}
                del change[dimension]
                with self.subTest(kind=kind, dimension=dimension, missing=True), self.assertRaises(ValueError):
                    run.summarize_answer({"text": "a"}, {kind: [change]}, .1)

    def test_every_raw_integer_is_accepted_and_printed_for_fixes_and_rewordings(self):
        for value in range(11):
            verdict = {"corrected": "b", "fixes": [{"from": "a", "to": "b", "why": "Fix",
                       "confidence": value, "impact": 10 - value}],
                       "natural": "c", "natural_changes": [{"from": "b", "to": "c", "why": "Reword",
                       "confidence": 10 - value, "impact": value}]}
            with self.subTest(value=value):
                failures, output = run.summarize_answer({"text": "a"}, verdict, .1)
                self.assertEqual([], failures)
                self.assertIn(f"Fix; confidence {value}/10; impact {10 - value}/10", output)
                self.assertIn(f"Reword; confidence {10 - value}/10; impact {value}/10", output)

    def test_history_uses_own_profile_not_later_session_preferences(self):
        row = {"session": {"id": "s", "schema": 6, "text": "estas", "writingProfiles": [self.profile("ca", "formal", "strict")],
               "attempts": [{"targetLanguage": "ca", "writingProfiles": [self.profile()]},
                            {"targetLanguage": "ca", "writingProfiles": [self.profile("ca", "formal", "strict")]}]},
               "suggestions": [{"id": "g", "attempt": 0, "kind": "fix", "start": 0, "end": 5, "fromText": "estas", "toText": "estàs"}]}
        original = copy.deepcopy(row)
        case = scoring.history_cases([row])[0]
        self.assertEqual("casual", case["context"]["register"])
        self.assertEqual("casual", case["context"]["punctuation"])
        self.assertEqual("Girona", case["context"]["variety"])
        self.assertEqual(original, row)
        row["session"]["attempts"][0].pop("writingProfiles")
        unknown = scoring.history_cases([row])[0]
        self.assertIsNone(unknown["context"]["register"])
        self.assertIsNone(unknown["context"]["punctuation"])


if __name__ == "__main__":
    unittest.main()
