"""Run the privileged stable publish shell offline, with only a fake gh executable."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/build.yml"
FAKE_GH = r'''
import json
import os
from pathlib import Path
import sys

path = Path(os.environ["FAKE_GH_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
state["calls"].append(args)
path.write_text(json.dumps(state))
if args[:2] == ["release", "create"]:
    tag = args[2]
    if any(release["tag_name"] == tag for release in state["releases"]):
        sys.exit("Release already exists")
    state["releases"].append({"tag_name": tag, "draft": False, "prerelease": False})
    if "--latest" in args:
        state["latest"] = tag
    elif "--latest=false" not in args:
        sys.exit("Create must explicitly disable Latest")
elif args[0] == "api":
    assert args == ["api", "--paginate", "--slurp", "repos/owner/repo/releases?per_page=100"]
    pages = [state["releases"][i:i + 100] for i in range(0, len(state["releases"]), 100)]
    if state.get("query_failure"):
        # Even a later-page failure with partial output must not move Latest.
        print(json.dumps(pages[:1]))
        sys.exit("Release query failed")
    print("invalid JSON" if state.get("invalid_json") else json.dumps(pages))
elif args[:2] == ["release", "edit"]:
    assert args[3:] == ["--latest"]
    assert any(release["tag_name"] == args[2] for release in state["releases"])
    state["latest"] = args[2]
else:
    sys.exit("Unexpected gh command")
path.write_text(json.dumps(state))
'''


class PublishReleaseTest(unittest.TestCase):
    @staticmethod
    def release(tag, **flags):
        return {"tag_name": tag, "draft": False, "prerelease": False, **flags}

    def publish(self, releases, version="v000232", **options):
        # Extract the actual workflow shell; never execute repository helpers in this job.
        step = WORKFLOW.read_text().split("      - name: Publish release\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n  # Run by hand", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state_path = root / "state.json"
            state_path.write_text(json.dumps({
                "releases": releases, "latest": "v000233", "calls": [], **options,
            }))
            gh = root / "gh"
            gh.write_text(f"#!{sys.executable}\n" + textwrap.dedent(FAKE_GH))
            gh.chmod(0o755)
            (root / "versioned.apk").write_bytes(b"offline APK fixture")
            (root / "versioned.apk.sha256").write_text("fixture checksum")
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", script],
                cwd=root, capture_output=True, text=True,
                env={**os.environ, "PATH": f"{root}:{os.environ['PATH']}",
                     "FAKE_GH_STATE": str(state_path), "RUNNER_TEMP": str(root),
                     "GH_REPO": "owner/repo", "GH_TOKEN": "unused-offline-token",
                     "VERSION": version, "APK": "versioned.apk", "SHA": "commit-sha",
                     "MESSAGE": "Build message", "CERT_SHA256": "fixture-fingerprint"},
            )
            return result, json.loads(state_path.read_text())

    def test_older_unpublished_build_keeps_newer_latest(self):
        result, state = self.publish([self.release("v000233")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("v000233", state["latest"])
        self.assertIn(self.release("v000232"), state["releases"])
        create = state["calls"][0]
        self.assertIn("--latest=false", create)
        self.assertEqual(["versioned.apk", "versioned.apk.sha256", "linguize.apk", "linguize.apk.sha256"],
                         create[3:7])

    def test_newer_build_advances_latest(self):
        result, state = self.publish([self.release("v000233")], version="v000234")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("v000234", state["latest"])

    def test_previews_drafts_inputs_and_unknown_tags_never_compete(self):
        releases = [self.release("v000233"), self.release("v999999", prerelease=True),
                    self.release("v999998", draft=True), self.release("branch-999997"),
                    self.release("build-inputs-999996"), self.release("v999995-inputs"),
                    self.release("v999994-preview"), self.release("unrelated-999993"),
                    self.release("build-1000000"), self.release("v000000")]
        result, state = self.publish(releases)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("v000233", state["latest"])

    def test_legacy_stable_tags_compare_numerically(self):
        result, state = self.publish([self.release("build-9"), self.release("build-1000"),
                                      self.release("v000999")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("build-1000", state["latest"])

    def test_highest_stable_can_be_beyond_first_page(self):
        releases = [self.release(f"v{number:06d}", prerelease=True) for number in range(300, 400)]
        releases.append(self.release("v000500"))
        result, state = self.publish(releases)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("v000500", state["latest"])

    def test_query_or_parse_failure_never_promotes(self):
        for option in ("query_failure", "invalid_json"):
            with self.subTest(option=option):
                result, state = self.publish([self.release("v000233")], **{option: True})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("v000233", state["latest"])
                self.assertFalse(any(call[:2] == ["release", "edit"] for call in state["calls"]))

    def test_existing_release_retry_still_fails_before_promotion(self):
        result, state = self.publish([self.release("v000233"), self.release("v000232")])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("v000233", state["latest"])
        self.assertEqual(1, len(state["calls"]))
