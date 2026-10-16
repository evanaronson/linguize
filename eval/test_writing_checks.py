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
        for value in [None, True, 1.5, "1", -1, 4]:
            verdict = {"fixes": [{"from": "a", "to": "b", "confidence": value, "impact": 1}]}
            with self.subTest(value=value), self.assertRaises(ValueError):
                run.summarize_answer({"text": "a"}, verdict, .1)
        verdict = {"corrected": "b", "fixes": [{"from": "a", "to": "b", "why": "Example", "confidence": 0, "impact": 3}]}
        failures, output = run.summarize_answer({"text": "a"}, verdict, .1)
        self.assertEqual([], failures)
        self.assertIn("confidence 0/3; impact 3/3", output)

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
