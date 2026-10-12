import unittest
import io
from unittest.mock import patch
import urllib.error

from releases import GitHub, identity, normalize, version


class ReleaseVersionsTest(unittest.TestCase):
    def test_rate_limit_is_paced_and_retried(self):
        error = urllib.error.HTTPError("url", 403, "Forbidden", {"Retry-After": "60"},
                                       io.BytesIO(b'{"message":"Secondary rate limit exceeded"}'))
        with patch("releases.urllib.request.urlopen", side_effect=[error, io.BytesIO(b'{}')]) as request, \
                patch("releases.time.sleep") as sleep:
            self.assertEqual({}, GitHub("owner/repo", "unused")("POST", "/git/refs", {}))
            self.assertEqual(2, request.call_count)
            self.assertIn(unittest.mock.call(60), sleep.call_args_list)

    def test_permission_errors_are_reported_without_blind_retries(self):
        error = urllib.error.HTTPError("url", 403, "Forbidden", {},
                                       io.BytesIO(b'{"message":"Resource not accessible by integration"}'))
        with patch("releases.urllib.request.urlopen", side_effect=error) as request, patch("releases.time.sleep"):
            with self.assertRaisesRegex(RuntimeError, "Resource not accessible by integration"):
                GitHub("owner/repo", "unused")("POST", "/git/refs", {})
            self.assertEqual(1, request.call_count)

    def test_text_order_matches_numeric_order(self):
        numbers = [9, 10, 98, 101, 104, 1000, 999_999]
        self.assertEqual([version(n) for n in numbers], sorted(version(n) for n in numbers))
        for invalid in [0, -1, 1_000_000]:
            with self.assertRaises(ValueError):
                version(invalid)

    def test_stable_preview_and_inputs_share_the_sequence(self):
        stable = self.release(1, "build-104")
        preview = self.release(2, "branch-88", True, "Branch build 88 (feature/navigation)")
        inputs = self.release(3, "build-inputs-51", True)
        self.assertEqual((104, "v000104", "v000104"), identity(stable))
        self.assertEqual((88, "v000088", "v000088 (preview: feature/navigation)"), identity(preview))
        self.assertEqual((51, "v000051-inputs", "v000051 (build inputs)"), identity(inputs))

    def test_migration_keeps_tag_targets_assets_and_preview_status(self):
        releases = [self.release(1, "build-98"), self.release(2, "build-104"),
                    self.release(3, "branch-110", True)]
        writes = []

        def api(method, path, body=None):
            if path.startswith("/releases?"):
                return releases
            self.assertEqual("PATCH", method)
            writes.append((method, path, body))
            release = next(r for r in releases if path == f"/releases/{r['id']}")
            release.update({k: v for k, v in body.items() if k != "make_latest"})
            return {}

        normalize(api, apply=True)
        self.assertEqual("build-104", releases[1]["tag_name"])
        self.assertEqual("v000104", releases[1]["name"])
        self.assertEqual("main", releases[1]["target_commitish"])
        self.assertEqual([{"name": "original.apk"}], releases[1]["assets"])
        self.assertEqual(("PATCH", "/releases/2", {"make_latest": "true"}), writes[-1])
        self.assertTrue(releases[2]["prerelease"])
        self.assertTrue(all(set(body) <= {"name", "make_latest"}
                            for method, _, body in writes if method == "PATCH"))
        writes.clear()
        normalize(api, apply=True)
        self.assertEqual([("PATCH", "/releases/2", {"make_latest": "true"})], writes)

    def test_dry_run_never_writes(self):
        calls = []

        def api(method, path, body=None):
            calls.append(method)
            return [self.release(1, "build-104")]

        normalize(api)
        self.assertEqual(["GET"], calls)

    def test_optional_retag_preserves_original_target_and_is_repeatable(self):
        release = self.release(1, "build-104", name="v000104")
        refs = {"build-104": "original-sha"}
        writes = []

        def api(method, path, body=None):
            if path.startswith("/releases?"):
                return [release]
            if method == "GET":
                tag = path.removeprefix("/git/ref/tags/")
                if tag not in refs:
                    raise urllib.error.HTTPError(path, 404, "Not Found", {}, None)
                return {"object": {"sha": refs[tag]}}
            writes.append((method, body))
            if method == "POST":
                refs[body["ref"].removeprefix("refs/tags/")] = body["sha"]
            else:
                release.update({k: v for k, v in body.items() if k != "make_latest"})

        normalize(api, apply=True, retag=True)
        self.assertEqual("v000104", release["tag_name"])
        self.assertEqual("original-sha", refs["build-104"])
        self.assertEqual("original-sha", refs["v000104"])
        writes.clear()
        normalize(api, apply=True, retag=True)
        self.assertEqual([("PATCH", {"make_latest": "true"})], writes)

    @staticmethod
    def release(id, tag, preview=False, name=None):
        return {"id": id, "tag_name": tag, "name": name or tag, "prerelease": preview,
                "draft": False, "target_commitish": "main", "assets": [{"name": "original.apk"}]}
