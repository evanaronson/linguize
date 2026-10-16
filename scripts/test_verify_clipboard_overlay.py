"""Offline shell regressions; fake SDK/adb commands never contact a device."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("verify_clipboard_overlay.sh")
SERVICES = "enabled_accessibility_services"
ENABLED = "accessibility_enabled"
PROBE = "com.evanaronson.linguize/.accessibility.ClipboardProbeService"
FAKE_COMMAND = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shlex
import sys

if Path(sys.argv[0]).name != "adb":
    sys.exit(0)
path = Path(os.environ["FAKE_STATE"])
state = json.loads(path.read_text())
args = sys.argv[1:]
if args[:1] == ["uninstall"]:
    assert args[1:] == ["com.linguize.clipboardprobe"], args
    state["helper_uninstalls"] = state.get("helper_uninstalls", 0) + 1
    if state.get("fail_uninstall"):
        path.write_text(json.dumps(state))
        sys.exit(27)
    state["helper_installed"] = False
    path.write_text(json.dumps(state))
elif args[:1] == ["install"]:
    state["installs"].append(args[1:])
    if args[1:] and args[1].endswith("seed.apk"):
        state["helper_installed"] = True
    path.write_text(json.dumps(state))
elif args[:2] == ["shell", "pm"] and args[2:4] == ["list", "packages"]:
    assert args[4:] == ["com.linguize.clipboardprobe"], args
    if state.get("helper_installed"):
        print("package:com.linguize.clipboardprobe")
if args[:1] == ["shell"]:
    # Like adb, join arguments before the remote shell interprets quotes.
    args = shlex.split(" ".join(args[1:]))
    if args[:3] == ["settings", "list", "secure"]:
        if state.get("fail_snapshot"):
            sys.exit(25)
        for key, value in state["settings"].items():
            print(f"{key}={value}")
    elif args[:1] == ["settings"]:
        operation, namespace, key, *values = args[1:]
        assert namespace == "secure"
        assert len(values) == (1 if operation == "put" else 0), args
        state["writes"].append([operation, key, *values])
        failure = state["fail_calls"].get(str(len(state["writes"])))
        # Initial writes may change settings before returning failure.
        if not failure or len(state["writes"]) <= 2:
            if operation == "put":
                state["settings"][key] = values[0]
            else:
                state["settings"].pop(key, None)
        path.write_text(json.dumps(state))
        if failure:
            sys.exit(failure)
    elif args[:1] == ["run-as"]:
        if args[2:] == ["rm", "-f", "files/clipboard-probe.txt"]:
            state["probe_delete"] = args[1:]
            path.write_text(json.dumps(state))
            if state.get("fail_probe_delete"):
                sys.exit(26)
        else:
            print(state["result"])
'''


class ClipboardSettingsTest(unittest.TestCase):
    def run_probe(self, settings, *, result="matches=true before=NoText", fail_calls=None,
                  fail_snapshot=False, fail_probe_delete=False, helper_installed=False,
                  fail_uninstall=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "fake-command"
            fake.write_text(FAKE_COMMAND)
            fake.chmod(0o755)
            for command in ["adb", "javac", "zip", "keytool", "sleep"]:
                (bin_dir / command).symlink_to(fake)
            build_tools = root / "sdk" / "build-tools" / "35.0.0"
            build_tools.mkdir(parents=True)
            for command in ["d8", "aapt", "apksigner"]:
                (build_tools / command).symlink_to(fake)
            temp_dir = root / "tmp"
            temp_dir.mkdir()
            state_path = root / "state.json"
            state_path.write_text(json.dumps({
                "settings": settings, "writes": [], "result": result,
                "fail_calls": fail_calls or {}, "fail_snapshot": fail_snapshot,
                "fail_probe_delete": fail_probe_delete,
                "helper_installed": helper_installed, "installs": [], "helper_uninstalls": 0,
                "fail_uninstall": fail_uninstall,
            }))
            process = subprocess.run(
                ["bash", str(SCRIPT)], cwd=root, text=True, capture_output=True,
                env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}",
                     "ANDROID_HOME": str(root / "sdk"), "TMPDIR": str(temp_dir),
                     "FAKE_STATE": str(state_path)}, timeout=15,
            )
            state = json.loads(state_path.read_text())
            self.assertEqual([], list(temp_dir.iterdir()), process.stderr)
            return process, state

    def test_exact_settings_restored_on_success_and_failed_assertions(self):
        originals = [
            {SERVICES: "other/.Service:second/.Service", ENABLED: "1"},
            {SERVICES: "", ENABLED: ""},
            {},
            {SERVICES: "null", ENABLED: "0"},
            {SERVICES: "other/.Service", ENABLED: ""},
            {ENABLED: "1"},
            {SERVICES: "other/.Service"},
            {SERVICES: "quote' space;$value", ENABLED: "0"},
        ]
        for original in originals:
            for result in ["matches=true before=NoText", "matches=false before=NoText",
                           "matches=true before=Text"]:
                with self.subTest(original=original, result=result):
                    settings = {"unrelated": "keep", **original}
                    process, state = self.run_probe(settings, result=result)
                    self.assertEqual(0 if result == "matches=true before=NoText" else 1,
                                     process.returncode, process.stderr)
                    self.assertEqual(settings, state["settings"])
                    initial_services = original.get(SERVICES, "")
                    self.assertEqual(["put", SERVICES,
                                      f"{initial_services}:{PROBE}" if initial_services else PROBE],
                                     state["writes"][0])
                    self.assertEqual([SERVICES, ENABLED],
                                     [write[1] for write in state["writes"][-2:]])

    def test_probe_already_listed_is_not_duplicated(self):
        for services in [PROBE, f"other/.Service:{PROBE}:last/.Service"]:
            with self.subTest(services=services):
                original = {SERVICES: services, ENABLED: "1"}
                process, state = self.run_probe(original)
                self.assertEqual(0, process.returncode, process.stderr)
                self.assertEqual(["put", SERVICES, services], state["writes"][0])
                self.assertEqual(original, state["settings"])

    def test_each_failed_initial_write_still_restores_both_keys(self):
        for original in [{}, {SERVICES: "other/.Service", ENABLED: "0"}]:
            for failed_call in [1, 2]:
                with self.subTest(original=original, failed_call=failed_call):
                    process, state = self.run_probe(original, fail_calls={str(failed_call): 23})
                    self.assertEqual(23, process.returncode, process.stderr)
                    self.assertEqual(original, state["settings"])
                    self.assertEqual([SERVICES, ENABLED],
                                     [write[1] for write in state["writes"][-2:]])

    def test_restore_failures_are_reported_and_both_restores_attempted(self):
        for result in ["matches=true before=NoText", "matches=false before=NoText"]:
            for failed_calls in [{"3": 24}, {"4": 24}, {"3": 24, "4": 24}]:
                with self.subTest(result=result, failed_calls=failed_calls):
                    process, state = self.run_probe({}, result=result, fail_calls=failed_calls)
                    self.assertEqual(1, process.returncode, process.stderr)
                    self.assertEqual([SERVICES, ENABLED],
                                     [write[1] for write in state["writes"][-2:]])
                    for call in failed_calls:
                        key = SERVICES if call == "3" else ENABLED
                        self.assertIn(f"Failed to restore {key}", process.stderr)

    def test_cleanup_failure_preserves_original_nonzero_status(self):
        process, state = self.run_probe({}, fail_calls={"2": 23, "3": 24})
        self.assertEqual(23, process.returncode, process.stderr)
        self.assertIn(f"Failed to restore {SERVICES}", process.stderr)
        self.assertEqual(4, len(state["writes"]))

    def test_failed_snapshot_does_not_change_settings(self):
        original = {SERVICES: "other/.Service", ENABLED: "1"}
        process, state = self.run_probe(original, fail_snapshot=True)
        self.assertEqual(25, process.returncode, process.stderr)
        self.assertEqual(original, state["settings"])
        self.assertEqual([], state["writes"])

    def test_stale_result_is_deleted_before_probe_is_enabled(self):
        process, state = self.run_probe({})
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(["com.evanaronson.linguize", "rm", "-f",
                          "files/clipboard-probe.txt"], state["probe_delete"])
        self.assertEqual(["put", SERVICES], state["writes"][0][:2])

    def test_stale_result_deletion_failure_aborts_before_enabling_probe(self):
        original = {SERVICES: "other/.Service", ENABLED: "1"}
        process, state = self.run_probe(original, fail_probe_delete=True)
        self.assertEqual(26, process.returncode, process.stderr)
        self.assertEqual(original, state["settings"])
        self.assertEqual([], state["writes"])

    def test_preexisting_helper_is_removed_before_fresh_install_and_cleanup(self):
        process, state = self.run_probe({}, helper_installed=True)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(2, state["helper_uninstalls"])
        self.assertEqual(2, len(state["installs"]))
        self.assertTrue(state["installs"][0][1].endswith("app-debug.apk"))
        self.assertTrue(state["installs"][1][0].endswith("seed.apk"))

    def test_no_helper_install_replacement_flag_is_used(self):
        process, state = self.run_probe({})
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(1, state["helper_uninstalls"])
        self.assertEqual(2, len(state["installs"]))
        self.assertNotIn("-r", state["installs"][1])

    def test_preinstall_removal_failure_stops_before_seed_install(self):
        process, state = self.run_probe({}, helper_installed=True, fail_uninstall=True)
        self.assertEqual(27, process.returncode, process.stderr)
        self.assertEqual(0, len(state["installs"]))


if __name__ == "__main__":
    unittest.main()
