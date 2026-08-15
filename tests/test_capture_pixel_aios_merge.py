import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_pixel_aios_merge",
    ROOT / "tools" / "capture_pixel_aios_merge.py",
)
capture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(capture)


SERIAL = "PIXEL9AFIXTURE"
SOURCE = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
TARGET = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081401:userdebug/test-keys"


class FakeAdb:
    def __init__(self, *, state="none", snapshots=0, boot_status="none",
                 fingerprint=TARGET, rollback_indicator="No such file or directory"):
        self.state = state
        self.snapshots = snapshots
        self.boot_status = boot_status
        self.rollback_indicator = rollback_indicator
        self.properties = {
            "sys.boot_completed": "1",
            "ro.gsid.image_running": "",
            "ro.product.device": "tegu",
            "ro.build.fingerprint": fingerprint,
            "ro.build.version.incremental": "2026081401",
            "ro.boot.slot_suffix": "_b",
            "ro.virtual_ab.enabled": "true",
            "ro.virtual_ab.compression.enabled": "true",
        }

    def run(self, arguments, *, serial=True):
        if arguments == ["devices", "-l"]:
            return (
                f"List of devices attached\n{SERIAL} device product:tegu\n"
                "emulator-5554 device product:emu64x\n"
            )
        if arguments[:2] == ["shell", "getprop"]:
            return self.properties[arguments[2]]
        if arguments[:3] == ["shell", "command", "-v"]:
            return f"/system/bin/{arguments[3]}"
        if arguments == ["shell", "snapshotctl", "dump"]:
            snapshot_lines = "".join(
                f"Snapshot: product_{index}\n" for index in range(self.snapshots)
            )
            source = "" if self.state == "none" else SOURCE
            return (
                f"Update state: {self.state}\n"
                "Using snapuserd: 0\n"
                "Current slot: _b\n"
                f"Rollback indicator: {self.rollback_indicator}\n"
                "Forward merge indicator: No such file or directory\n"
                f"Source build fingerprint: {source}\n"
                f"{snapshot_lines}"
            )
        if arguments == ["shell", "bootctl", "get-snapshot-merge-status"]:
            return self.boot_status
        if arguments in (
            ["shell", "bootctl", "get-current-slot"],
            ["shell", "bootctl", "get-active-boot-slot"],
        ):
            return "1"
        if arguments == [
            "shell", "bootctl", "is-slot-marked-successful", "1"
        ]:
            return "Command succeeded"
        raise AssertionError(arguments)


class PixelAiosMergeEvidenceTests(unittest.TestCase):
    def post_update_record(self, raw):
        value = {
            "schema_version": 1,
            "status": "passed",
            "kind": "pixel9a_aios_virtual_ab_post_update_boot",
            "serial_sha256": capture.boot_capture.text_sha256(SERIAL),
            "build_fingerprint": TARGET,
            "source_fingerprint": SOURCE,
            "source_slot": "_a",
            "active_slot": "_b",
            "properties": {
                "ro.product.device": "tegu",
                "ro.build.fingerprint": TARGET,
                "ro.build.version.incremental": "2026081401",
                "ro.boot.slot_suffix": "_b",
                "ro.virtual_ab.enabled": "true",
                "ro.virtual_ab.compression.enabled": "true",
            },
            "checks": {
                "build_ota_update_chain_verified": True,
                "exact_target_fingerprint": True,
                "inactive_slot_became_active": True,
            },
            "proves_update_engine_command_passed": True,
            "proves_post_update_boot": True,
            "proves_slot_switch": True,
            "proves_merge_completed": False,
        }
        path = Path(raw) / "post-update.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return value, path

    def test_captures_exact_completed_merge_without_mutating_device(self):
        with tempfile.TemporaryDirectory() as raw:
            post_update, path = self.post_update_record(raw)
            self.assertEqual(
                post_update, capture.validate_post_update(path, SERIAL)
            )
            value = capture.collect(FakeAdb(), post_update, SERIAL, path)
            self.assertTrue(value["proves_merge_completed"])
            self.assertEqual("none", value["snapshot_update_state"])
            self.assertEqual("none", value["boot_control_merge_status"])
            self.assertEqual(0, value["snapshot_count"])
            self.assertEqual(
                hashlib.sha256(path.read_bytes()).hexdigest(),
                value["post_update_evidence_sha256"],
            )

    def test_rejects_in_progress_or_residual_snapshot_state(self):
        with tempfile.TemporaryDirectory() as raw:
            post_update, path = self.post_update_record(raw)
            for runner in (
                FakeAdb(state="merging", boot_status="merging"),
                FakeAdb(state="none", snapshots=1),
                FakeAdb(state="none", boot_status="snapshotted"),
                FakeAdb(state="none", rollback_indicator="Permission denied"),
            ):
                with self.subTest(state=runner.state, snapshots=runner.snapshots,
                                  boot_status=runner.boot_status):
                    with self.assertRaisesRegex(
                        capture.MergeEvidenceError, "incomplete"
                    ):
                        capture.collect(runner, post_update, SERIAL, path)

    def test_rejects_tampered_chain_or_different_running_build(self):
        with tempfile.TemporaryDirectory() as raw:
            post_update, path = self.post_update_record(raw)
            tampered = json.loads(json.dumps(post_update))
            tampered["proves_merge_completed"] = True
            path.write_text(json.dumps(tampered), encoding="utf-8")
            with self.assertRaisesRegex(
                capture.MergeEvidenceError, "does not bind"
            ):
                capture.validate_post_update(path, SERIAL)
            with self.assertRaisesRegex(
                capture.MergeEvidenceError, "fingerprint"
            ):
                capture.collect(
                    FakeAdb(fingerprint=SOURCE), post_update, SERIAL, path
                )

    def test_refuses_output_inside_source_tree(self):
        with self.assertRaisesRegex(capture.MergeEvidenceError, "outside source"):
            capture.write_json_atomic(ROOT / "never-write-merge.json", {})


if __name__ == "__main__":
    unittest.main()
