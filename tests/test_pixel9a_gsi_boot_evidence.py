import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "validate_pixel9a_gsi_boot_evidence",
    ROOT / "tools" / "validate_pixel9a_gsi_boot_evidence.py",
)
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Pixel9aGsiBootEvidenceTests(unittest.TestCase):
    def fixtures(self, raw):
        root = Path(raw)
        inventory_path = root / "inventory.json"
        preflight_path = root / "preflight.json"
        build_path = root / "build.json"
        evidence_path = root / "boot.json"
        inventory = {
            "schema_version": 2,
            "status": "captured",
            "adb_state": "device",
            "serial_sha256": digest("serial"),
        }
        artifacts = [
            {"path": "pvmfw.img", "size_bytes": 5, "sha256": digest("pvmfw")},
            {"path": "system.img", "size_bytes": 100, "sha256": digest("system")},
            {"path": "vbmeta.img", "size_bytes": 10, "sha256": digest("vbmeta")},
            {
                "path": "system/product/priv-app/AiosPhone/AiosPhone.apk",
                "size_bytes": 20,
                "sha256": digest("phone"),
            },
            {
                "path": "system/product/etc/aios/model_catalog.json",
                "size_bytes": 30,
                "sha256": digest("catalog"),
            },
        ]
        build = {
            "schema_version": 2,
            "status": "passed",
            "lane": "android_gsi_arm64",
            "product": "aios_gsi_arm64",
            "target_device": "generic_arm64",
            "android_release": "17",
            "security_patch": "2026-06-05",
            "build_fingerprint": "AIOS/test/generic_arm64:17/test:userdebug/test-keys",
            "deployable_images": ["pvmfw.img", "system.img", "vbmeta.img"],
            "proves_physical_runtime_gate": False,
            "artifacts": artifacts,
        }
        inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
        build_path.write_text(json.dumps(build), encoding="utf-8")
        images = {
            name: {
                "size_bytes": next(x for x in artifacts if x["path"] == name)["size_bytes"],
                "sha256": next(x for x in artifacts if x["path"] == name)["sha256"],
            }
            for name in ("pvmfw.img", "system.img", "vbmeta.img")
        }
        preflight = {
            "schema_version": 1,
            "status": "candidate",
            "expected_device": "tegu",
            "observed_device": "tegu",
            "dsu_candidate": True,
            "safe_to_flash": False,
            "proves_gsi_compatibility": False,
            "proves_physical_runtime_gate": False,
            "inventory_sha256": validator.sha256(inventory_path),
            "build_evidence_sha256": validator.sha256(build_path),
            "avb_evidence_sha256": digest("avb"),
            "dsu_payload_evidence_sha256": digest("dsu"),
            "system_interface_evidence_sha256": digest("interface"),
            "gsi_images": images,
        }
        preflight_path.write_text(json.dumps(preflight), encoding="utf-8")
        properties = {
            "sys.boot_completed": "1",
            "ro.gsid.image_running": "1",
            "ro.build.fingerprint": build["build_fingerprint"],
            "ro.build.type": "userdebug",
            "ro.build.version.release": "17",
            "ro.build.version.security_patch": "2026-06-05",
            "ro.product.cpu.abilist64": "arm64-v8a",
        }
        evidence = {
            "schema_version": 1,
            "status": "passed",
            "kind": "pixel9a_gsi_dsu_first_boot",
            "collected_at_utc": "2026-08-13T07:00:00+00:00",
            "serial_sha256": inventory["serial_sha256"],
            "inventory_sha256": validator.sha256(inventory_path),
            "preflight_sha256": validator.sha256(preflight_path),
            "build_evidence_sha256": validator.sha256(build_path),
            "avb_evidence_sha256": preflight["avb_evidence_sha256"],
            "dsu_payload_evidence_sha256": preflight["dsu_payload_evidence_sha256"],
            "system_interface_evidence_sha256": preflight[
                "system_interface_evidence_sha256"
            ],
            "images": images,
            "build_fingerprint": build["build_fingerprint"],
            "properties": properties,
            "packages": {
                package: f"package:/system/product/priv-app/Test/{package}.apk"
                for package in validator.REQUIRED_PACKAGES
            },
            "dialer_role_holders": ["com.aios.phone"],
            "default_dialer_overlay": "com.aios.phone",
            "installed_artifacts": [
                {
                    "path": "/" + item["path"],
                    "size_bytes": item["size_bytes"],
                    "sha256": item["sha256"],
                }
                for item in artifacts if item["path"].startswith("system/")
            ],
            "checks": {name: True for name in validator.EXPECTED_CHECKS},
            "proves_gsi_compatibility": True,
            "proves_boot_first_boot": True,
            "proves_physical_runtime_gate": False,
            "proves_telephony_gate": False,
            "proves_model_latency_gate": False,
            "proves_media_gate": False,
            "proves_factory_restore": False,
        }
        evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
        return evidence_path, inventory_path, preflight_path, build_path

    def test_accepts_exact_bounded_first_boot_record(self):
        with tempfile.TemporaryDirectory() as raw:
            paths = self.fixtures(raw)
            value = validator.validate(*paths)
            self.assertTrue(value["proves_boot_first_boot"])
            self.assertFalse(value["proves_physical_runtime_gate"])

    def test_rejects_tampered_installed_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            paths = self.fixtures(raw)
            evidence = json.loads(paths[0].read_text(encoding="utf-8"))
            evidence["installed_artifacts"][0]["sha256"] = "0" * 64
            paths[0].write_text(json.dumps(evidence), encoding="utf-8")
            with self.assertRaisesRegex(
                validator.PixelBootEvidenceError,
                "installed AIOS artifacts",
            ):
                validator.validate(*paths)

    def test_rejects_runtime_gate_overclaim(self):
        with tempfile.TemporaryDirectory() as raw:
            paths = self.fixtures(raw)
            evidence = json.loads(paths[0].read_text(encoding="utf-8"))
            evidence["proves_physical_runtime_gate"] = True
            paths[0].write_text(json.dumps(evidence), encoding="utf-8")
            with self.assertRaisesRegex(
                validator.PixelBootEvidenceError,
                "overclaims",
            ):
                validator.validate(*paths)

    def test_rejects_evidence_after_preflight_changes(self):
        with tempfile.TemporaryDirectory() as raw:
            paths = self.fixtures(raw)
            preflight = json.loads(paths[2].read_text(encoding="utf-8"))
            preflight["evaluated_at"] = "later"
            paths[2].write_text(json.dumps(preflight), encoding="utf-8")
            with self.assertRaisesRegex(
                validator.PixelBootEvidenceError,
                "input chain",
            ):
                validator.validate(*paths)


if __name__ == "__main__":
    unittest.main()
