"""Run the cleanup workflow against isolated local Git refs, never GitHub."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


RUN = subprocess.run
WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/cleanup-branches.yml"
REPO = "owner/repo"
REMOTE = f"https://github.com/{REPO}.git"


class BranchCleanupTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.remote = Path(self.folder.name) / "remote.git"
        RUN(["git", "init", "--bare", "--quiet", str(self.remote)], check=True)
        tree = self.git("mktree", input="").stdout.strip()
        self.old = self.git("commit-tree", tree, input="merged\n").stdout.strip()
        self.new = self.git("commit-tree", tree, "-p", self.old, input="new work\n").stdout.strip()
        self.pushes = []
        self.output = io.StringIO()

    def git(self, *args, input=None):
        return RUN(["git", "-C", str(self.remote), "-c", "user.name=Cleanup test",
                    "-c", "user.email=cleanup@example.invalid", *args], input=input,
                   check=True, capture_output=True, text=True)

    def branch(self, name, sha=None, protected=False):
        sha = sha or self.old
        self.git("update-ref", f"refs/heads/{name}", sha)
        return {"name": name, "commit": {"sha": sha}, "protected": protected}

    def pull(self, name, sha=None, state="closed", merged=True, repo=REPO):
        return {"head": {"ref": name, "sha": sha or self.old,
                         "repo": {"full_name": repo} if repo else None},
                "state": state, "merged_at": "2026-10-16" if merged else None}

    def run_cleanup(self, branches, pulls, before_push=None, push_result=None):
        script = textwrap.dedent(WORKFLOW.read_text().split("python3 - <<'PY'\n", 1)[1]
                                 .rsplit("          PY", 1)[0])

        def run(command, **kwargs):
            if command[0] == "gh":
                self.assertEqual(["gh", "api", "--method", "GET"], command[:4])
                path = command[4]
                responses = {
                    f"repos/{REPO}": {"default_branch": "main"},
                    f"repos/{REPO}/pulls?state=all&per_page=100": [pulls],
                    f"repos/{REPO}/branches?per_page=100": [branches],
                }
                return subprocess.CompletedProcess(command, 0, json.dumps(responses[path]))
            self.assertEqual("git", command[0])
            self.assertNotIn("unused-cleanup-secret", " ".join(command))
            if "push" in command:
                self.pushes.append(command)
                self.assertIn("--porcelain", command)
                self.assertIn("credential.helper=", command)
                self.assertIn("credential.helper=!gh auth git-credential", command)
                self.assertEqual("0", kwargs["env"]["GIT_TERMINAL_PROMPT"])
                self.assertEqual("C", kwargs["env"]["LC_ALL"])
                if before_push:
                    before_push()
                if push_result:
                    return subprocess.CompletedProcess(command, *push_result)
                # Only this known URL can be substituted; all Git operations remain local.
                self.assertIn(REMOTE, command)
                command = [str(self.remote) if arg == REMOTE else arg for arg in command]
            else:
                self.assertEqual(["git", "init", "--bare", "--quiet"], command[:4])
            return RUN(command, **kwargs)

        with patch.dict(os.environ, {"GH_REPO": REPO, "GH_TOKEN": "unused-cleanup-secret"}), \
                patch("subprocess.run", side_effect=run), contextlib.redirect_stdout(self.output):
            exec(compile(script, str(WORKFLOW), "exec"), {})

    def test_exact_lease_deletes_unchanged_slash_named_branch(self):
        name = "feature/finished"
        self.run_cleanup([self.branch(name)], [self.pull(name)])
        self.assertEqual("", self.git("for-each-ref", f"refs/heads/{name}").stdout)
        self.assertEqual(1, len(self.pushes))
        self.assertIn(f"--force-with-lease=refs/heads/{name}:{self.old}", self.pushes[0])
        self.assertEqual(f":refs/heads/{name}", self.pushes[0][-1])
        self.assertIn(f"Delete {name} at {self.old}", self.output.getvalue())

    def test_push_after_enumeration_rejects_stale_lease_and_preserves_new_work(self):
        name = "feature/new-work"
        self.run_cleanup([self.branch(name)], [self.pull(name)],
                         before_push=lambda: self.git("update-ref", f"refs/heads/{name}", self.new))
        self.assertEqual(self.new, self.git("rev-parse", f"refs/heads/{name}").stdout.strip())
        self.assertIn(f"Keep {name}: branch moved or disappeared", self.output.getvalue())
        self.assertNotIn("Delete ", self.output.getvalue())

    def test_branch_deleted_after_enumeration_is_safe(self):
        name = "feature/already-gone"
        self.run_cleanup([self.branch(name)], [self.pull(name)],
                         before_push=lambda: self.git("update-ref", "-d", f"refs/heads/{name}"))
        self.assertEqual("", self.git("for-each-ref", f"refs/heads/{name}").stdout)
        self.assertIn(f"Keep {name}: branch moved or disappeared", self.output.getvalue())
        self.assertNotIn("Delete ", self.output.getvalue())

    def test_default_protected_open_pr_and_exact_merged_tip_guards(self):
        names = ["main", "protected", "open", "unmerged", "wrong-tip", "wrong-name",
                 "fork", "deleted-fork", "finished"]
        branches = [self.branch(name, protected=name == "protected") for name in names]
        pulls = [self.pull("main"), self.pull("protected"), self.pull("open"),
                 self.pull("open", state="open", merged=False),
                 self.pull("unmerged", merged=False), self.pull("wrong-tip", sha=self.new),
                 self.pull("other-name"), self.pull("fork", repo="fork/repo"),
                 self.pull("deleted-fork", repo=None), self.pull("finished")]
        self.run_cleanup(branches, pulls)
        self.assertEqual(1, len(self.pushes))
        self.assertEqual(":refs/heads/finished", self.pushes[0][-1])
        for name in names[:-1]:
            self.assertEqual(self.old, self.git("rev-parse", f"refs/heads/{name}").stdout.strip())

    def test_retired_branch_requires_its_exact_listed_sha(self):
        name = "feature/settings-home-test-build"
        retired_sha = "65601a11764d28b83b86d1f767baaa1d055c49e4"
        branches = [{"name": name, "commit": {"sha": retired_sha}, "protected": False},
                    self.branch("feature/shortcut-tap-probe"),
                    self.branch("different-name")]
        self.run_cleanup(branches, [], push_result=(0, "", ""))
        self.assertEqual(1, len(self.pushes))
        self.assertIn(f"--force-with-lease=refs/heads/{name}:{retired_sha}", self.pushes[0])
        self.assertIn("Keep feature/shortcut-tap-probe: no merged PR", self.output.getvalue())

    def test_permission_authentication_and_transport_failures_remain_failures(self):
        for stdout, stderr in [
            ("!\t(delete):refs/heads/finished\t[remote rejected] (permission denied)\n", "permission denied"),
            ("", "fatal: Authentication failed"),
            ("", "fatal: Could not resolve host: github.com"),
        ]:
            with self.subTest(error=stderr):
                self.output = io.StringIO()
                with self.assertRaisesRegex(RuntimeError, stderr):
                    self.run_cleanup([self.branch("finished")], [self.pull("finished")],
                                     push_result=(1, stdout, stderr))
                self.assertEqual(self.old, self.git("rev-parse", "refs/heads/finished").stdout.strip())
                self.assertNotIn("Keep ", self.output.getvalue())
                self.assertNotIn("Delete ", self.output.getvalue())


if __name__ == "__main__":
    unittest.main()
