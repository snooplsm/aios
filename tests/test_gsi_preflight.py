import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "check_gsi_preflight", ROOT / "tools" / "check_gsi_preflight.py"
)
preflight = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(preflight)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def inventory() -> dict:
    return {
        "schema_version": 2,
        "status": "captured",
        "serial_sha256": digest("test-serial"),
        "adb_state": "device",
        "properties": {
            "ro.product.manufacturer": "Google",
            "ro.product.device": "tegu",
            "ro.product.cpu.abilist64": "arm64-v8a",
            "ro.build.version.release": "16",
            "ro.build.version.security_patch": "2026-05-05",
            "ro.vendor.api_level": "35",
            "ro.vndk.version": "35",
            "ro.treble.enabled": "true",
            "ro.boot.dynamic_partitions": "true",
            "ro.boot.flash.locked": "1",
        },
        "capabilities": {"dynamic_system_feature": "true"},
        "collection": {
            "read_only": True,
            "unlock_attempted": False,
            "flash_attempted": False,
        },
        "proves_gsi_compatibility": False,
        "proves_physical_runtime_gate": False,
    }


def build() -> dict:
    names = [
        "system.img",
        "vbmeta.img",
        *[
            f"system/product/priv-app/{name}/{name}.apk"
            for name in preflight.REQUIRED_PACKAGES
        ],
    ]
    return {
        "schema_version": 2,
        "status": "passed",
        "lane": "android_gsi_arm64",
        "kind": "generic_system_image",
        "product": "aios_gsi_arm64",
        "target_device": "generic_arm64",
        "android_release": "17",
        "security_patch": "2026-06-05",
        "artifact_layout": "gsi_system_product",
        "deployable_images": ["system.img", "vbmeta.img"],
        "installed_files_manifest": "installed-files-system.json",
        "lane_eligible_for_physical_gates": True,
        "proves_physical_runtime_gate": False,
        "artifacts": [
            {"path": name, "size_bytes": len(name), "sha256": digest(name)}
            for name in names
        ],
    }


class GsiPreflightTests(unittest.TestCase):
    def write_inputs(self, raw, inventory_value=None, build_value=None):
        base = Path(raw)
        inventory_path = base / "inventory.json"
        build_path = base / "build.json"
        inventory_path.write_text(
            json.dumps(inventory_value or inventory()), encoding="utf-8"
        )
        build_path.write_text(json.dumps(build_value or build()), encoding="utf-8")
        return inventory_path, build_path

    def test_marks_structural_pixel9a_match_as_candidate_not_safe(self):
        with tempfile.TemporaryDirectory() as raw:
            inventory_path, build_path = self.write_inputs(raw)
            value = preflight.evaluate(inventory_path, build_path, "tegu")
            self.assertEqual("candidate", value["status"])
            self.assertTrue(value["dsu_candidate"])
            self.assertTrue(value["fastboot_candidate"])
            self.assertTrue(value["bootloader_locked"])
            self.assertFalse(value["safe_to_flash"])
            self.assertFalse(value["proves_gsi_compatibility"])
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertIn("system.img", value["gsi_images"])
            self.assertGreaterEqual(len(value["blockers"]), 5)

    def test_rejects_wrong_architecture_as_incompatible(self):
        with tempfile.TemporaryDirectory() as raw:
            value = inventory()
            value["properties"]["ro.product.cpu.abilist64"] = "x86_64"
            inventory_path, build_path = self.write_inputs(raw, value)
            result = preflight.evaluate(inventory_path, build_path, "tegu")
            self.assertEqual("incompatible", result["status"])
            self.assertFalse(result["checks"]["arm64_userspace"])
            self.assertFalse(result["fastboot_candidate"])

    def test_older_gsi_patch_is_not_a_dsu_candidate(self):
        with tempfile.TemporaryDirectory() as raw:
            build_value = build()
            build_value["security_patch"] = "2026-04-05"
            inventory_path, build_path = self.write_inputs(
                raw, build_value=build_value
            )
            value = preflight.evaluate(inventory_path, build_path, "tegu")
            self.assertEqual("incompatible", value["status"])
            self.assertFalse(value["checks"]["system_patch_not_older"])
            self.assertFalse(value["dsu_candidate"])

    def test_refuses_build_that_claims_physical_runtime(self):
        with tempfile.TemporaryDirectory() as raw:
            build_value = build()
            build_value["proves_physical_runtime_gate"] = True
            inventory_path, build_path = self.write_inputs(
                raw, build_value=build_value
            )
            with self.assertRaisesRegex(preflight.GsiPreflightError,
                                        "exact ARM64 GSI"):
                preflight.evaluate(inventory_path, build_path, "tegu")


if __name__ == "__main__":
    unittest.main()
