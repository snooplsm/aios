import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_pixel_aios_boot", ROOT / "tools" / "capture_pixel_aios_boot.py"
)
capture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(capture)


SERIAL = "PIXEL9AFIXTURE"
FINGERPRINT = "AIOS/aios_tegu/tegu:17/FIXTURE/1:userdebug/test-keys"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FakeAdb:
    def __init__(self, artifacts, overrides=None):
        self.artifacts = {"/" + item["path"]: item for item in artifacts}
        self.properties = {
            "sys.boot_completed": "1",
            "ro.gsid.image_running": "",
            "ro.build.fingerprint": FINGERPRINT,
            "ro.build.type": "userdebug",
            "ro.build.version.release": "17",
            "ro.build.version.security_patch": "2026-08-05",
            "ro.product.device": "tegu",
            "ro.product.vendor.device": "tegu",
            "ro.product.cpu.abilist64": "arm64-v8a",
            "ro.boot.verifiedbootstate": "orange",
            "ro.boot.flash.locked": "0",
            "ro.boot.vbmeta.device_state": "unlocked",
            "ro.crypto.state": "encrypted",
            "ro.crypto.type": "file",
            "ro.aios.version": "0.1-dev",
        }
        self.properties.update(overrides or {})

    def run(self, arguments, *, serial=True):
        if arguments == ["devices", "-l"]:
            return f"List of devices attached\n{SERIAL} device product:tegu\n"
        if arguments[:2] == ["shell", "getprop"]:
            return self.properties[arguments[2]]
        if arguments[:5] == ["shell", "cmd", "user", "is-user-unlocked", "0"]:
            return "true"
        if arguments[:5] == ["shell", "settings", "get", "secure", "user_setup_complete"]:
            return "1"
        if arguments[:3] == ["shell", "pm", "path"]:
            package_name = arguments[3]
            return f"package:/product/priv-app/{package_name}/base.apk"
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


class PixelAiosBootEvidenceTests(unittest.TestCase):
    def records(self, raw):
        base = Path(raw)
        artifacts = [
            {
                "path": f"product/etc/aios/fixture-{index}.bin",
                "size_bytes": index + 1,
                "sha256": hashlib.sha256(f"fixture-{index}".encode()).hexdigest(),
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
            "build_fingerprint": FINGERPRINT,
            "android_release": "17",
            "security_patch": "2026-08-05",
            "target_files_package": {"sha256": "a" * 64},
            "generated_payloads": {"model_pack": {}, "runtime_packs": []},
            "artifacts": artifacts,
        }
        build_path = base / "build.json"
        build_path.write_text(json.dumps(build), encoding="utf-8")
        release = {
            "schema_version": 1,
            "status": "passed",
            "lane": "pixel9a_tegu_hardware",
            "target_device": "tegu",
            "build_fingerprint": FINGERPRINT,
            "build_evidence_sha256": digest(build_path),
            "target_files_sha256": "a" * 64,
            "contains_required_model_payloads": True,
            "fastboot_archive": {"sha256": "b" * 64},
        }
        release_path = base / "release.json"
        release_path.write_text(json.dumps(release), encoding="utf-8")
        flash = {
            "schema_version": 1,
            "status": "flash_command_passed",
            "kind": "pixel9a_aios_development_flash",
            "flashed": True,
            "wipe_requested": True,
            "proves_flash_command_passed": True,
            "proves_first_boot": False,
            "release_evidence_sha256": digest(release_path),
            "fastboot_archive_sha256": "b" * 64,
            "serial_sha256": capture.text_sha256(SERIAL),
        }
        flash_path = base / "flash.json"
        flash_path.write_text(json.dumps(flash), encoding="utf-8")
        return build, release, flash, build_path, release_path, flash_path

    def test_captures_exact_full_device_boot_and_artifacts(self):
        with tempfile.TemporaryDirectory() as raw:
            build, release, flash, build_path, release_path, flash_path = \
                self.records(raw)
            chained = capture.validate_chain(
                build_path, release_path, flash_path, SERIAL
            )
            self.assertEqual((build, release, flash), chained)
            value = capture.collect(
                FakeAdb(build["artifacts"]), build, release, flash, SERIAL
            )
            self.assertTrue(value["proves_boot_first_boot"])
            self.assertTrue(value["proves_model_payload_install"])
            self.assertFalse(value["proves_telephony_gate"])
            self.assertEqual(34, len(value["installed_artifacts"]))

    def test_rejects_wrong_fingerprint_and_gsi(self):
        with tempfile.TemporaryDirectory() as raw:
            build, release, flash, *_ = self.records(raw)
            for overrides, message in (
                ({"ro.build.fingerprint": "wrong"}, "fingerprint"),
                ({"ro.gsid.image_running": "1"}, "GSI"),
            ):
                with self.subTest(message=message), self.assertRaisesRegex(
                        capture.BootEvidenceError, message):
                    capture.collect(
                        FakeAdb(build["artifacts"], overrides),
                        build, release, flash, SERIAL,
                    )

    def test_rejects_flash_chain_for_another_serial(self):
        with tempfile.TemporaryDirectory() as raw:
            *_, build_path, release_path, flash_path = self.records(raw)
            with self.assertRaisesRegex(capture.BootEvidenceError, "flash record"):
                capture.validate_chain(
                    build_path, release_path, flash_path, "OTHER"
                )


if __name__ == "__main__":
    unittest.main()
