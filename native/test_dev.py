"""Guard against verification falsely passing or targeting a physical device."""
import copy
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import dev


class VerificationChecks(unittest.TestCase):
    def test_missing_sensor_archive_or_reviewed_metadata_stops_before_commands(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(dev, "capture") as capture:
            root = Path(directory)
            archive = root / "sensor.zip"
            with patch.dict(dev.os.environ, {"SAMSUNG_SENSOR_SDK": str(archive)}), patch.object(dev, "ROOT", root):
                with self.assertRaisesRegex(RuntimeError, "Missing Samsung Sensor SDK"):
                    dev.toolchain()
                archive.write_bytes(b"not opened by missing-metadata preflight")
                with self.assertRaisesRegex(RuntimeError, "Missing reviewed Gradle"):
                    dev.toolchain()
            capture.assert_not_called()

    def test_changed_selection_includes_deleted_shared_sources_and_resources(self):
        before = {"native/phone/src/A.kt": "a", "native/sync/src/B.kt": "b"}
        after = {"native/phone/src/A.kt": "a", "native/wear/src/C.kt": "c"}
        self.assertEqual(["native/sync/src/B.kt", "native/wear/src/C.kt"], dev.changed_paths(before, after))
        self.assertFalse(dev.affects("native/wear/src/C.kt", "phone"))
        for path in ("native/sync/src/B.kt", "android/res/font/manrope.ttf", "native/build.gradle.kts"):
            self.assertTrue(all(dev.affects(path, m) for m in dev.MODULES))
        self.assertFalse(dev.affects("android/src/Recorder.java", "wear"))

    def test_instrumentation_requires_completed_positive_run_without_ignored_or_failed_checks(self):
        good = "INSTRUMENTATION_STATUS_CODE: 0\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual(2, dev.instrumentation_count(good))
        for bad in ("", "OK (0 tests)\nINSTRUMENTATION_CODE: -1", "OK (2 tests)", good + good,
                    good + "FAILURES!!!", good + "INSTRUMENTATION_STATUS_CODE: -4", good + "shortMsg=Process crashed"):
            with self.subTest(output=bad), self.assertRaises(RuntimeError):
                dev.instrumentation_count(bad)

    def test_physical_serial_is_rejected_before_adb(self):
        with patch.object(dev, "capture") as capture:
            for serial in (None, "", "192.0.2.1:1234", "physical-device", "emulator-5582; echo bad"):
                with self.assertRaises(ValueError):
                    dev.device_info("adb", serial)
            capture.assert_not_called()

    def test_baseline_cannot_relabel_replay_skipped_or_inconsistent_source_as_full_pass(self):
        baseline = {"schema": 1, "module": "phone", "mode": "full", "status": "passed",
                    "source": {"files": {"a": "hash"}, "fingerprint": dev.fingerprint({"a": "hash"})},
                    "checks": [{"name": "instrumentation", "status": "passed", "testsPassed": 2}]}
        dev.validate_baseline(baseline, "phone")
        for key, value in (("mode", "replay"), ("module", "wear"), ("status", "unchanged"), ("checks", [])):
            with self.assertRaises(ValueError):
                dev.validate_baseline(dict(baseline, **{key: value}), "phone")
        changed = copy.deepcopy(baseline)
        changed["source"]["files"]["a"] = "changed"
        with self.assertRaises(ValueError):
            dev.validate_baseline(changed, "phone")


if __name__ == "__main__":
    unittest.main()
