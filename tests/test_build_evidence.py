import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_build_evidence", ROOT / "tools" / "capture_build_evidence.py"
)
evidence = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(evidence)


def git(checkout, *arguments):
    result = subprocess.run(
        ["git", "-c", "core.autocrlf=false", *arguments],
        cwd=checkout,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout.strip()


class BuildEvidenceTests(unittest.TestCase):
    def create_fixture(
            self,
            raw,
            lane_id="android_latest_integration",
            product="aios_cf_x86_64_phone",
            target_device="vsoc_x86_64"):
        base = Path(raw)
        aios = base / "aios"
        (aios / "config").mkdir(parents=True)
        (aios / "patches").mkdir(parents=True)
        shutil.copy(ROOT / "config" / "aosp_lanes.json",
                    aios / "config" / "aosp_lanes.json")
        shutil.copy(ROOT / "patches" / "series.json",
                    aios / "patches" / "series.json")
        patch_series = json.loads((aios / "patches" / "series.json")
                                  .read_text(encoding="utf-8"))
        for item in patch_series["patches"]:
            shutil.copy(ROOT / "patches" / item["file"],
                        aios / "patches" / item["file"])
        git(aios, "init")
        git(aios, "config", "core.autocrlf", "false")
        git(aios, "config", "user.name", "AIOS Test")
        git(aios, "config", "user.email", "test@aios.invalid")
        git(aios, "add", "config/aosp_lanes.json", "patches")
        git(aios, "commit", "-m", "fixture")
        head = git(aios, "rev-parse", "HEAD")

        manifest = base / "aosp-manifest.xml"
        manifest.write_text("<manifest><project name=\"aios\" /></manifest>\n",
                            encoding="utf-8")
        manifest_lock = base / "aosp-manifest-lock.json"
        manifest_lock.write_text(json.dumps({
            "schema_version": 1,
            "lane": lane_id,
            "product": product,
            "aios_revision": head,
            "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
            "manifest_repository_revision": "f" * 40,
        }), encoding="utf-8")

        out = base / "out"
        product_out = out / "target" / "product" / target_device
        (product_out / "product" / "etc").mkdir(parents=True)
        (product_out / "system").mkdir(parents=True)
        (product_out / "product" / "etc" / "build.prop").write_text(
            "ro.aios.version=0.1-dev\n", encoding="utf-8"
        )
        (product_out / "system" / "build.prop").write_text(
            "ro.build.fingerprint=aios/test/fingerprint\n"
            "ro.build.version.release=17\n",
            encoding="utf-8",
        )
        lanes = json.loads((aios / "config" / "aosp_lanes.json")
                           .read_text(encoding="utf-8"))
        for relative in lanes["expected_product_artifacts"]:
            target = product_out / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(f"fixture:{relative}".encode())
        installed = []
        for relative in lanes["expected_product_artifacts"]:
            target = product_out / relative
            installed.append({
                "Name": "/" + relative,
                "Size": target.stat().st_size,
                "SHA256": hashlib.sha256(target.read_bytes()).hexdigest(),
            })
        (product_out / "installed-files-product.json").write_text(
            json.dumps(installed), encoding="utf-8"
        )
        (product_out / "product.img").write_bytes(b"product-image")
        (product_out / "system.img").write_bytes(b"system-image")
        log = base / "soong-build.log"
        log.write_text("build completed successfully\n", encoding="utf-8")
        return aios, manifest, manifest_lock, out, log, product_out

    def test_captures_digest_bound_product_outputs(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, _ = self.create_fixture(raw)
            output = Path(raw) / "build-evidence.json"
            value = evidence.capture(
                aios, "android_latest_integration", manifest, lock, out, log, output
            )
            self.assertEqual("passed", value["status"])
            self.assertEqual(2, value["schema_version"])
            self.assertEqual("aios/test/fingerprint", value["build_fingerprint"])
            self.assertEqual("f" * 40, value["manifest_repository_revision"])
            self.assertEqual(15, len(value["artifacts"]))
            self.assertEqual(2, len(value["patch_queue"]))
            self.assertRegex(value["patch_queue_sha256"], r"^[0-9a-f]{64}$")
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertEqual(
                hashlib.sha256(
                    (out / "target" / "product" / "vsoc_x86_64" /
                     "installed-files-product.json").read_bytes()
                ).hexdigest(),
                value["installed_files_product_sha256"],
            )
            self.assertTrue(output.is_file())

    def test_accepts_legacy_product_root_build_prop(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            installed = product_out / "product" / "etc" / "build.prop"
            legacy = product_out / "product" / "build.prop"
            installed.replace(legacy)
            value = evidence.capture(
                aios, "android_latest_integration", manifest, lock, out, log
            )
            self.assertEqual("passed", value["status"])

    def test_accepts_legacy_partition_relative_manifest_paths(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            installed_path = product_out / "installed-files-product.json"
            installed = json.loads(installed_path.read_text(encoding="utf-8"))
            for item in installed:
                item["Name"] = item["Name"].removeprefix("/product")
            installed_path.write_text(json.dumps(installed), encoding="utf-8")
            value = evidence.capture(
                aios, "android_latest_integration", manifest, lock, out, log
            )
            self.assertEqual("passed", value["status"])

    def test_captures_android_emulator_lane_without_physical_claim(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, _ = self.create_fixture(
                raw,
                lane_id="android_avd_integration",
                product="aios_sdk_phone_x86_64",
                target_device="emu64x",
            )
            value = evidence.capture(
                aios, "android_avd_integration", manifest, lock, out, log
            )
            self.assertEqual("virtual_emulator", value["kind"])
            self.assertEqual("aios_sdk_phone_x86_64", value["product"])
            self.assertEqual("emu64x", value["target_device"])
            self.assertFalse(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])

    def test_rejects_lock_without_manifest_repository_revision(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, _ = self.create_fixture(raw)
            value = json.loads(lock.read_text(encoding="utf-8"))
            value.pop("manifest_repository_revision")
            lock.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(evidence.BuildEvidenceError, "repository revision"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_patch_queue_record_rejects_payload_tampering(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, _, _, _, _, _ = self.create_fixture(raw)
            series = json.loads((aios / "patches" / "series.json")
                                .read_text(encoding="utf-8"))
            payload = aios / "patches" / series["patches"][0]["file"]
            payload.write_bytes(payload.read_bytes() + b"\ntampered\n")
            with self.assertRaisesRegex(evidence.BuildEvidenceError, "digest mismatch"):
                evidence.patch_queue_record(aios)

    def test_patch_queue_digest_binds_review_metadata(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, _, _, _, _, _ = self.create_fixture(raw)
            _, before = evidence.patch_queue_record(aios)
            series_path = aios / "patches" / "series.json"
            series = json.loads(series_path.read_text(encoding="utf-8"))
            series["patches"][0]["rebase_notes"] += " Reconfirm the owner review."
            series_path.write_text(json.dumps(series), encoding="utf-8")
            _, after = evidence.patch_queue_record(aios)
            self.assertNotEqual(before, after)

    def test_rejects_manifest_changed_after_lock(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, _ = self.create_fixture(raw)
            manifest.write_text("<manifest />\n", encoding="utf-8")
            with self.assertRaisesRegex(evidence.BuildEvidenceError, "digest"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_missing_installed_aios_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            (product_out / "product" / "priv-app" / "AiosPhone" /
             "AiosPhone.apk").unlink()
            with self.assertRaisesRegex(evidence.BuildEvidenceError, "AiosPhone"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_missing_default_dialer_overlay(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            (product_out / "product" / "overlay" /
             "AiosFrameworkDefaultsOverlay.apk").unlink()
            with self.assertRaisesRegex(evidence.BuildEvidenceError,
                                        "AiosFrameworkDefaultsOverlay"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_missing_new_communication_apps(self):
        for module in ("AiosMessaging", "AiosContextIntelligence"):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as raw:
                aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
                (product_out / "product" / "priv-app" / module /
                 f"{module}.apk").unlink()
                with self.assertRaisesRegex(evidence.BuildEvidenceError, module):
                    evidence.capture(
                        aios, "android_latest_integration", manifest, lock, out, log
                    )

    def test_rejects_empty_installed_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            target = (product_out / "product" / "priv-app" / "AiosMessaging" /
                      "AiosMessaging.apk")
            target.write_bytes(b"")
            with self.assertRaisesRegex(
                    evidence.BuildEvidenceError, "empty.*AiosMessaging"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_artifact_missing_from_current_product_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            installed_path = product_out / "installed-files-product.json"
            installed = json.loads(installed_path.read_text(encoding="utf-8"))
            installed = [
                item for item in installed
                if "AiosMessaging.apk" not in item["Name"]
            ]
            installed_path.write_text(json.dumps(installed), encoding="utf-8")
            with self.assertRaisesRegex(
                    evidence.BuildEvidenceError, "absent.*AiosMessaging"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_missing_installed_product_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            (product_out / "installed-files-product.json").unlink()
            with self.assertRaisesRegex(
                    evidence.BuildEvidenceError, "missing installed-files-product"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )

    def test_rejects_installed_manifest_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(raw)
            installed_path = product_out / "installed-files-product.json"
            installed = json.loads(installed_path.read_text(encoding="utf-8"))
            next(item for item in installed
                 if "AiosContextIntelligence.apk" in item["Name"])["SHA256"] = "0" * 64
            installed_path.write_text(json.dumps(installed), encoding="utf-8")
            with self.assertRaisesRegex(
                    evidence.BuildEvidenceError, "digest.*AiosContextIntelligence"):
                evidence.capture(
                    aios, "android_latest_integration", manifest, lock, out, log
                )


if __name__ == "__main__":
    unittest.main()
