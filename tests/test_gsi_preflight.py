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
        "capabilities": {
            "dynamic_system_feature": "true",
            "data_filesystem": (
                "Filesystem 1K-blocks Used Available Use% Mounted on\n"
                "/dev/block/dm-1 40000000 1000000 39000000 3% /data"
            ),
        },
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
        "aios_revision": "1" * 40,
        "lane": "android_gsi_arm64",
        "kind": "generic_system_image",
        "product": "aios_gsi_arm64",
        "target_device": "generic_arm64",
        "android_release": "17",
        "security_patch": "2026-06-05",
        "artifact_layout": "gsi_system_product",
        "deployable_images": ["system.img", "vbmeta.img"],
        "installed_files_manifest": "installed-files.json",
        "lane_eligible_for_physical_gates": True,
        "proves_physical_runtime_gate": False,
        "artifacts": [
            {"path": name, "size_bytes": len(name), "sha256": digest(name)}
            for name in names
        ],
    }


class GsiPreflightTests(unittest.TestCase):
    @staticmethod
    def avb(build_value, build_path):
        artifacts = {
            item["path"]: item for item in build_value["artifacts"]
        }
        return {
            "schema_version": 1,
            "status": "passed",
            "kind": "gsi_avb_chain_verification",
            "aios_revision": build_value.get("aios_revision"),
            "build_evidence_sha256": preflight.sha256(build_path),
            "expected_chain_partition": {
                "partition": "system",
                "rollback_index_location": 1,
                "public_key_sha1": preflight.EXPECTED_GSI_PUBLIC_KEY_SHA1,
                "algorithm": "SHA256_RSA2048",
            },
            "images": {
                name: {
                    "size_bytes": artifacts[name]["size_bytes"],
                    "sha256": artifacts[name]["sha256"],
                }
                for name in ("system.img", "vbmeta.img")
            },
            "checks": {name: True for name in preflight.EXPECTED_AVB_CHECKS},
            "lane_eligible_for_physical_gates": True,
            "proves_physical_runtime_gate": False,
        }

    @staticmethod
    def dsu_payload(build_value, build_path):
        system = next(
            item for item in build_value["artifacts"]
            if item["path"] == "system.img"
        )
        return {
            "schema_version": 1,
            "status": "passed",
            "kind": "gsi_dsu_payload",
            "aios_revision": build_value.get("aios_revision"),
            "build_evidence_sha256": preflight.sha256(build_path),
            "source_image": {
                "name": "system.img",
                "format": "raw_ext4_with_avb_footer",
                "size_bytes": system["size_bytes"],
                "sha256": system["sha256"],
            },
            "payload": {
                "name": "17.test.raw.gz",
                "format": "gzip",
                "compression_level": 1,
                "size_bytes": max(1, system["size_bytes"] - 1),
                "uncompressed_size_bytes": system["size_bytes"],
                "sha256": digest("gzip"),
            },
            "checks": {
                "gzip_integrity_verified": True,
                "stream_decompression_sha256_verified": True,
            },
            "external_payload_only": True,
            "safe_to_install": False,
            "proves_physical_runtime_gate": False,
        }
    def write_inputs(self, raw, inventory_value=None, build_value=None):
        base = Path(raw)
        inventory_path = base / "inventory.json"
        build_path = base / "build.json"
        selected_build = build_value or build()
        inventory_path.write_text(
            json.dumps(inventory_value or inventory()), encoding="utf-8"
        )
        build_path.write_text(json.dumps(selected_build), encoding="utf-8")
        (base / "avb-verification.json").write_text(
            json.dumps(self.avb(selected_build, build_path)), encoding="utf-8"
        )
        (base / "dsu-payload.json").write_text(
            json.dumps(self.dsu_payload(selected_build, build_path)),
            encoding="utf-8",
        )
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
            self.assertEqual("system", value["avb_chain"]["partition"])
            self.assertEqual(64, len(value["avb_evidence_sha256"]))
            self.assertEqual("gzip", value["dsu_payload"]["format"])
            self.assertEqual(64, len(value["dsu_payload_evidence_sha256"]))
            self.assertTrue(value["dsu_checks"]["data_free_space_sufficient"])
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
            self.assertEqual("candidate", value["status"])
            self.assertFalse(value["checks"]["system_patch_not_older"])
            self.assertFalse(value["dsu_candidate"])
            self.assertTrue(value["fastboot_candidate"])
            self.assertNotIn("system_patch_not_older",
                             value["fastboot_structural_checks"])

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

    def test_dsu_candidate_requires_space_for_exact_image_and_userdata(self):
        with tempfile.TemporaryDirectory() as raw:
            inventory_value = inventory()
            inventory_value["capabilities"]["data_filesystem"] = (
                "Filesystem 1K-blocks Used Available Use% Mounted on\n"
                "/dev/block/dm-1 9000000 1000000 8000000 12% /data"
            )
            inventory_path, build_path = self.write_inputs(
                raw, inventory_value=inventory_value
            )
            value = preflight.evaluate(inventory_path, build_path, "tegu")
            self.assertEqual("candidate", value["status"])
            self.assertFalse(value["dsu_candidate"])
            self.assertFalse(value["dsu_checks"]["data_free_space_sufficient"])
            self.assertGreater(value["dsu_storage"]["required_bytes"],
                               value["dsu_storage"]["available_bytes"])

    def test_accepts_legacy_partition_specific_installed_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            build_value = build()
            build_value["installed_files_manifest"] = "installed-files-system.json"
            inventory_path, build_path = self.write_inputs(
                raw, build_value=build_value
            )
            value = preflight.evaluate(inventory_path, build_path, "tegu")
            self.assertEqual("candidate", value["status"])

    def test_rejects_avb_evidence_not_bound_to_build(self):
        with tempfile.TemporaryDirectory() as raw:
            inventory_path, build_path = self.write_inputs(raw)
            avb_path = Path(raw) / "avb-verification.json"
            avb_value = json.loads(avb_path.read_text(encoding="utf-8"))
            avb_value["build_evidence_sha256"] = "0" * 64
            avb_path.write_text(json.dumps(avb_value), encoding="utf-8")
            with self.assertRaisesRegex(preflight.GsiPreflightError,
                                        "AVB evidence is not bound"):
                preflight.evaluate(inventory_path, build_path, "tegu")

    def test_rejects_dsu_payload_not_bound_to_system_image(self):
        with tempfile.TemporaryDirectory() as raw:
            inventory_path, build_path = self.write_inputs(raw)
            payload_path = Path(raw) / "dsu-payload.json"
            payload_value = json.loads(payload_path.read_text(encoding="utf-8"))
            payload_value["source_image"]["sha256"] = "0" * 64
            payload_path.write_text(json.dumps(payload_value), encoding="utf-8")
            with self.assertRaisesRegex(preflight.GsiPreflightError,
                                        "DSU payload evidence is not bound"):
                preflight.evaluate(inventory_path, build_path, "tegu")


if __name__ == "__main__":
    unittest.main()
