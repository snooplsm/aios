import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "exercise_pixel_rollback",
    ROOT / "tools" / "exercise_pixel_rollback.py",
)
rollback = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(rollback)


SERIAL = "PIXEL9AFIXTURE"
SOURCE = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
TARGET = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081401:userdebug/test-keys"


class FakeAdb:
    def __init__(self):
        self.current = "0"
        self.active = "1"
        self.snapshot_state = "unverified"
        self.snapshot_count = 4
        self.boot_status = "snapshotted"
        self.commands = []

    def run(self, arguments, *, serial=True):
        self.commands.append(tuple(arguments))
        if arguments == ["devices", "-l"]:
            return (
                f"List of devices attached\n{SERIAL} device product:tegu\n"
                "emulator-5554 device product:emu64x\n"
            )
        if arguments[:2] == ["shell", "getprop"]:
            values = {
                "sys.boot_completed": "1",
                "ro.gsid.image_running": "",
                "ro.product.device": "tegu",
                "ro.build.fingerprint": SOURCE,
                "ro.build.version.incremental": "2026081300",
                "ro.boot.slot_suffix": "_a",
                "ro.virtual_ab.enabled": "true",
                "ro.virtual_ab.compression.enabled": "true",
            }
            return values[arguments[2]]
        if arguments[:3] == ["shell", "command", "-v"]:
            return f"/system/bin/{arguments[3]}"
        if arguments == ["shell", "bootctl", "get-current-slot"]:
            return self.current
        if arguments == ["shell", "bootctl", "get-active-boot-slot"]:
            return self.active
        if arguments[:3] == ["shell", "bootctl", "set-active-boot-slot"]:
            self.active = arguments[3]
            return ""
        if arguments[:3] in (
            ["shell", "bootctl", "is-slot-bootable"],
            ["shell", "bootctl", "is-slot-marked-successful"],
        ):
            if arguments[3] == "0":
                return "1"
            raise rollback.boot_capture.BootEvidenceError("unexpected target flag")
        if arguments == ["shell", "bootctl", "get-snapshot-merge-status"]:
            return self.boot_status
        if arguments == ["shell", "snapshotctl", "dump"]:
            snapshots = "".join(
                f"Snapshot: product_{index}\n" for index in range(self.snapshot_count)
            )
            source_fingerprint = SOURCE if self.snapshot_state != "none" else ""
            return (
                f"Update state: {self.snapshot_state}\n"
                "Current slot: _a\n"
                "Rollback indicator: No such file or directory\n"
                "Forward merge indicator: No such file or directory\n"
                f"Source build fingerprint: {source_fingerprint}\n"
                f"{snapshots}"
            )
        raise AssertionError(arguments)

    def observe_rollback_boot(self):
        self.current = "0"
        self.active = "0"
        self.snapshot_state = "none"
        self.snapshot_count = 0
        self.boot_status = "none"


def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True), encoding="utf-8")


class PixelRollbackExerciseTests(unittest.TestCase):
    def chain(self, directory):
        ota = {
            "schema_version": 1,
            "status": "passed",
            "update_kind": "full_virtual_ab_ota",
            "lane": "pixel9a_tegu_hardware",
            "product": "aios_tegu",
            "target_device": "tegu",
            "build_fingerprint": TARGET,
            "virtual_ab_compression": "true",
            "contains_required_model_payloads": True,
            "installation_performed": False,
            "ota_archive": {"sha256": "a" * 64},
        }
        ota_path = directory / "ota.json"
        write_json(ota_path, ota)
        update = {
            "schema_version": 1,
            "status": "update_engine_command_passed",
            "kind": "pixel9a_aios_virtual_ab_update",
            "serial_sha256": rollback.boot_capture.text_sha256(SERIAL),
            "ota_evidence_sha256": rollback.boot_capture.sha256(ota_path),
            "ota_archive_sha256": ota["ota_archive"]["sha256"],
            "source_fingerprint": SOURCE,
            "target_fingerprint": TARGET,
            "source_slot": "_a",
            "expected_target_slot": "_b",
            "payload_applicability_verified": True,
            "payload_space_allocated": True,
            "staging_removed": True,
            "reboot_performed": False,
            "proves_update_engine_command_passed": True,
            "proves_post_update_boot": False,
            "proves_slot_switch": False,
            "proves_merge_completed": False,
        }
        update_path = directory / "update.json"
        write_json(update_path, update)
        return ota, ota_path, update, update_path

    def test_cancels_unverified_update_before_merge_and_requires_fresh_update(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            ota, ota_path, update, update_path = self.chain(directory)
            self.assertEqual(
                (ota, update), rollback.validate_chain(ota_path, update_path, SERIAL)
            )
            runner = FakeAdb()
            preflight = rollback.preflight(runner, update, SERIAL)
            self.assertTrue(preflight["eligible"])
            self.assertEqual("unverified", preflight["snapshot_update_state"])
            self.assertEqual("snapshotted", preflight["boot_control_merge_status"])
            token = rollback.confirmation_token(SERIAL, update)
            self.assertEqual("ROLLBACK-PIXEL9AFIXTURE-TO-2026081300", token)
            prepared = rollback.arm_rollback(
                runner,
                ota,
                update,
                SERIAL,
                ota_path,
                update_path,
                token,
            )
            self.assertEqual("_a", prepared["post_active_slot"])
            self.assertFalse(prepared["target_boot_performed"])
            prepare_path = directory / "rollback-prepare.json"
            write_json(prepare_path, prepared)

            runner.observe_rollback_boot()
            completed = rollback.capture_rollback(
                runner,
                ota,
                update,
                SERIAL,
                ota_path,
                update_path,
                prepare_path,
            )
            self.assertTrue(completed["proves_rollback"])
            self.assertTrue(completed["proves_source_slot_boot"])
            self.assertFalse(completed["proves_post_update_boot"])
            self.assertFalse(completed["proves_merge_completed"])
            self.assertTrue(completed["fresh_update_required"])
            self.assertNotIn(("shell", "reboot"), runner.commands)

    def test_refuses_merge_started_or_missing_snapshot_window(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            _, _, update, _ = self.chain(directory)
            for state, count, boot_status in (
                ("merging", 4, "merging"),
                ("none", 0, "none"),
                ("unverified", 0, "snapshotted"),
            ):
                runner = FakeAdb()
                runner.snapshot_state = state
                runner.snapshot_count = count
                runner.boot_status = boot_status
                with self.subTest(state=state, count=count):
                    with self.assertRaisesRegex(
                        rollback.RollbackEvidenceError, "rollback window"
                    ):
                        rollback.preflight(runner, update, SERIAL)

    def test_wrong_confirmation_cannot_mutate_slot(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            ota, ota_path, update, update_path = self.chain(directory)
            runner = FakeAdb()
            with self.assertRaisesRegex(
                rollback.RollbackEvidenceError, "exact confirmation"
            ):
                rollback.arm_rollback(
                    runner,
                    ota,
                    update,
                    SERIAL,
                    ota_path,
                    update_path,
                    "yes",
                )
            self.assertEqual("1", runner.active)
            self.assertFalse(any(
                command[:3] == ("shell", "bootctl", "set-active-boot-slot")
                for command in runner.commands
            ))

    def test_capture_requires_reboot_cleanup_and_untampered_chain(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            ota, ota_path, update, update_path = self.chain(directory)
            runner = FakeAdb()
            prepared = rollback.arm_rollback(
                runner,
                ota,
                update,
                SERIAL,
                ota_path,
                update_path,
                rollback.confirmation_token(SERIAL, update),
            )
            prepare_path = directory / "prepare.json"
            write_json(prepare_path, prepared)
            with self.assertRaisesRegex(
                rollback.RollbackEvidenceError, "fully removed"
            ):
                rollback.capture_rollback(
                    runner, ota, update, SERIAL, ota_path, update_path, prepare_path
                )
            prepared["target_boot_performed"] = True
            write_json(prepare_path, prepared)
            runner.observe_rollback_boot()
            with self.assertRaisesRegex(
                rollback.RollbackEvidenceError, "preparation record"
            ):
                rollback.capture_rollback(
                    runner, ota, update, SERIAL, ota_path, update_path, prepare_path
                )

    def test_refuses_output_inside_source_tree(self):
        with self.assertRaisesRegex(
            rollback.RollbackEvidenceError, "outside source"
        ):
            rollback.write_json_atomic(ROOT / "never-write-rollback.json", {})


if __name__ == "__main__":
    unittest.main()
