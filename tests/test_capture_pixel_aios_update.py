import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_pixel_aios_update",
    ROOT / "tools" / "capture_pixel_aios_update.py",
)
capture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(capture)


SERIAL = "PIXEL9AFIXTURE"
SOURCE = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
TARGET = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081401:userdebug/test-keys"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FakeAdb:
    def __init__(self, artifacts, overrides=None):
        self.artifacts = {"/" + item["path"]: item for item in artifacts}
        self.properties = {
            "sys.boot_completed": "1",
            "sys.user.0.ce_available": "true",
            "ro.gsid.image_running": "",
            "ro.build.fingerprint": TARGET,
            "ro.build.type": "userdebug",
            "ro.build.version.release": "17",
            "ro.build.version.security_patch": "2026-08-05",
            "ro.build.version.incremental": "2026081401",
            "ro.build.date.utc": "1786749300",
            "ro.product.device": "tegu",
            "ro.product.vendor.device": "tegu",
            "ro.product.cpu.abilist64": "arm64-v8a",
            "ro.boot.verifiedbootstate": "orange",
            "ro.boot.flash.locked": "0",
            "ro.boot.vbmeta.device_state": "unlocked",
            "ro.boot.slot_suffix": "_b",
            "ro.crypto.state": "encrypted",
            "ro.crypto.type": "file",
            "ro.virtual_ab.enabled": "true",
            "ro.virtual_ab.compression.enabled": "true",
            "ro.aios.version": "0.1-dev",
        }
        self.properties.update(overrides or {})

    def run(self, arguments, *, serial=True):
        if arguments == ["devices", "-l"]:
            return (
                f"List of devices attached\n{SERIAL} device product:tegu\n"
                "emulator-5554 device product:emu64x\n"
            )
        if arguments[:2] == ["shell", "getprop"]:
            return self.properties[arguments[2]]
        if arguments[:5] == ["shell", "settings", "get", "secure", "user_setup_complete"]:
            return "1"
        if arguments[:3] == ["shell", "pm", "path"]:
            return f"package:/product/priv-app/{arguments[3]}/base.apk"
        if arguments[:4] == ["shell", "cmd", "role", "get-role-holders"]:
            return "com.aios.phone"
        if arguments[:4] == ["shell", "cmd", "overlay", "lookup"]:
            return "com.aios.phone"
        if arguments[:4] == ["shell", "stat", "-c", "%s"]:
            return str(self.artifacts[arguments[4]]["size_bytes"])
        if arguments[:2] == ["shell", "sha256sum"]:
            item = self.artifacts[arguments[2]]
            return f"{item['sha256']}  {arguments[2]}"
        raise AssertionError(arguments)


class PixelAiosUpdateEvidenceTests(unittest.TestCase):
    def records(self, raw):
        base = Path(raw)
        artifacts = [
            {
                "path": f"product/etc/aios/update-fixture-{index}.bin",
                "size_bytes": index + 1,
                "sha256": hashlib.sha256(f"update-{index}".encode()).hexdigest(),
            }
            for index in range(34)
        ]
        build = {
            "schema_version": 2,
            "status": "passed",
            "lane": "pixel9a_tegu_hardware",
            "product": "aios_tegu",
            "target_device": "tegu",
            "artifact_layout": "full_device_target_files",
            "build_fingerprint": TARGET,
            "build_incremental": "2026081401",
            "build_timestamp": 1786749300,
            "android_release": "17",
            "security_patch": "2026-08-05",
            "target_files_package": {"sha256": "a" * 64},
            "generated_payloads": {"model_pack": {}, "runtime_packs": []},
            "artifacts": artifacts,
        }
        build_path = base / "build.json"
        build_path.write_text(json.dumps(build), encoding="utf-8")
        ota = {
            "schema_version": 1,
            "status": "passed",
            "update_kind": "full_virtual_ab_ota",
            "lane": "pixel9a_tegu_hardware",
            "product": "aios_tegu",
            "target_device": "tegu",
            "build_fingerprint": TARGET,
            "security_patch": "2026-08-05",
            "build_evidence_sha256": digest(build_path),
            "target_files_sha256": "a" * 64,
            "contains_required_model_payloads": True,
            "installation_performed": False,
            "ota_archive": {"sha256": "b" * 64},
            "ota_metadata": {
                "post-build": TARGET,
                "post-build-incremental": "2026081401",
                "post-timestamp": "1786749300",
                "post-security-patch-level": "2026-08-05",
            },
            "signature_verification": {
                "status": "passed",
                "whole_file_and_payload_verified": True,
            },
        }
        ota_path = base / "ota.json"
        ota_path.write_text(json.dumps(ota), encoding="utf-8")
        update = {
            "schema_version": 1,
            "status": "update_engine_command_passed",
            "kind": "pixel9a_aios_virtual_ab_update",
            "serial_sha256": capture.boot_capture.text_sha256(SERIAL),
            "ota_evidence_sha256": digest(ota_path),
            "ota_archive_sha256": "b" * 64,
            "source_fingerprint": SOURCE,
            "target_fingerprint": TARGET,
            "source_slot": "_a",
            "expected_target_slot": "_b",
            "staging_removed": True,
            "reboot_performed": False,
            "proves_update_engine_command_passed": True,
            "proves_post_update_boot": False,
            "proves_slot_switch": False,
            "proves_merge_completed": False,
        }
        update_path = base / "update.json"
        update_path.write_text(json.dumps(update), encoding="utf-8")
        return build, ota, update, build_path, ota_path, update_path

    def test_captures_exact_post_update_slot_and_artifacts(self):
        with tempfile.TemporaryDirectory() as raw:
            build, ota, update, build_path, ota_path, update_path = self.records(raw)
            chained = capture.validate_chain(
                build_path, ota_path, update_path, SERIAL
            )
            self.assertEqual((build, ota, update), chained)
            value = capture.collect(
                FakeAdb(build["artifacts"]), build, ota, update, SERIAL,
                build_path, ota_path, update_path,
            )
            self.assertTrue(value["proves_post_update_boot"])
            self.assertTrue(value["proves_slot_switch"])
            self.assertFalse(value["proves_merge_completed"])
            self.assertEqual("_b", value["active_slot"])
            self.assertEqual(34, len(value["installed_artifacts"]))

    def test_rejects_wrong_slot_and_tampered_update_chain(self):
        with tempfile.TemporaryDirectory() as raw:
            build, ota, update, build_path, ota_path, update_path = self.records(raw)
            bad = json.loads(json.dumps(update))
            bad["ota_archive_sha256"] = "c" * 64
            update_path.write_text(json.dumps(bad), encoding="utf-8")
            with self.assertRaisesRegex(capture.UpdateEvidenceError, "does not bind"):
                capture.validate_chain(build_path, ota_path, update_path, SERIAL)
            update_path.write_text(json.dumps(update), encoding="utf-8")
            with self.assertRaisesRegex(capture.UpdateEvidenceError, "slot_suffix"):
                capture.collect(
                    FakeAdb(build["artifacts"], {"ro.boot.slot_suffix": "_a"}),
                    build,
                    ota,
                    update,
                    SERIAL,
                    build_path,
                    ota_path,
                    update_path,
                )


if __name__ == "__main__":
    unittest.main()
