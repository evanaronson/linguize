import unittest
import urllib.error

from releases import identity, normalize, version


class ReleaseVersionsTest(unittest.TestCase):
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
        refs = {"build-98": "sha98", "build-104": "sha104", "branch-110": "sha110"}
        writes = []

        def api(method, path, body=None):
            if path.startswith("/releases?"):
                return releases
            if method == "GET":
                tag = path.removeprefix("/git/ref/tags/")
                if tag not in refs:
                    raise urllib.error.HTTPError(path, 404, "Not Found", {}, None)
                return {"object": {"sha": refs[tag]}}
            writes.append((method, path, body))
            if method == "POST":
                refs[body["ref"].removeprefix("refs/tags/")] = body["sha"]
            else:
                release = next(r for r in releases if path == f"/releases/{r['id']}")
                release.update({k: v for k, v in body.items() if k != "make_latest"})
            return {}

        normalize(api, apply=True)
        self.assertEqual("sha104", refs["v000104"])
        self.assertEqual("sha104", refs["build-104"])
        self.assertEqual(("PATCH", "/releases/2", {"make_latest": "true"}), writes[-1])
        self.assertTrue(releases[2]["prerelease"])
        self.assertTrue(all(set(body) <= {"tag_name", "name", "make_latest"}
                            for method, _, body in writes if method == "PATCH"))
        writes.clear()
        normalize(api, apply=True)
        self.assertEqual([("PATCH", "/releases/2", {"make_latest": "true"})], writes)

    def test_conflicting_alias_fails_before_any_writes(self):
        writes = []

        def api(method, path, body=None):
            if path.startswith("/releases?"):
                return [self.release(1, "build-104")]
            if method == "GET":
                return {"object": {"sha": "old" if path.endswith("build-104") else "different"}}
            writes.append(body)

        with self.assertRaises(ValueError):
            normalize(api, apply=True)
        self.assertEqual([], writes)

    @staticmethod
    def release(id, tag, preview=False, name=None):
        return {"id": id, "tag_name": tag, "name": name or tag, "prerelease": preview,
                "draft": False, "target_commitish": "main", "assets": [{"name": "original.apk"}]}
