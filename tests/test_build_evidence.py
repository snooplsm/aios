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
        lanes = json.loads((aios / "config" / "aosp_lanes.json")
                           .read_text(encoding="utf-8"))
        lane = next(item for item in lanes["lanes"] if item["id"] == lane_id)
        expected_artifacts = [
            evidence.installed_artifact_path(lane, relative)
            for relative in lanes["expected_product_artifacts"]
        ]
        product_root = (product_out / "system" / "product"
                        if lane["artifact_layout"] == "gsi_system_product"
                        else product_out / "product")
        (product_root / "etc").mkdir(parents=True)
        (product_out / "system").mkdir(parents=True, exist_ok=True)
        (product_root / "etc" / "build.prop").write_text(
            "ro.aios.version=0.1-dev\n", encoding="utf-8"
        )
        (product_out / "system" / "build.prop").write_text(
            "ro.build.fingerprint=aios/test/fingerprint\n"
            "ro.build.version.release=17\n"
            "ro.build.version.security_patch=2026-06-05\n",
            encoding="utf-8",
        )
        for relative in expected_artifacts:
            target = product_out / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(f"fixture:{relative}".encode())
        installed = []
        for relative in expected_artifacts:
            target = product_out / relative
            installed.append({
                "Name": "/" + relative,
                "Size": target.stat().st_size,
                "SHA256": hashlib.sha256(target.read_bytes()).hexdigest(),
            })
        manifest_name = ("installed-files-system.json"
                         if lane["artifact_layout"] == "gsi_system_product"
                         else "installed-files-product.json")
        (product_out / manifest_name).write_text(
            json.dumps(installed), encoding="utf-8"
        )
        for image in lane["required_images"]:
            (product_out / image).write_bytes(f"fixture:{image}".encode())
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
            self.assertEqual("2026-06-05", value["security_patch"])
            self.assertEqual(15, len(value["artifacts"]))
            self.assertEqual(3, len(value["patch_queue"]))
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

    def test_captures_arm64_gsi_layout_and_deployable_images(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, _, = self.create_fixture(
                raw,
                lane_id="android_gsi_arm64",
                product="aios_gsi_arm64",
                target_device="generic_arm64",
            )
            value = evidence.capture(
                aios, "android_gsi_arm64", manifest, lock, out, log
            )
            self.assertEqual("generic_system_image", value["kind"])
            self.assertEqual("gsi_system_product", value["artifact_layout"])
            self.assertEqual(["system.img", "vbmeta.img"],
                             value["deployable_images"])
            self.assertEqual("installed-files-system.json",
                             value["installed_files_manifest"])
            self.assertTrue(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])
            paths = {item["path"] for item in value["artifacts"]}
            self.assertIn(
                "system/product/priv-app/AiosPhone/AiosPhone.apk", paths
            )
            self.assertNotIn("product.img", paths)

    def test_captures_model_and_platform_signed_runtime_payloads(self):
        with tempfile.TemporaryDirectory() as raw:
            aios, manifest, lock, out, log, product_out = self.create_fixture(
                raw,
                lane_id="android_gsi_arm64",
                product="aios_gsi_arm64",
                target_device="generic_arm64",
            )
            (aios / ".git" / "info" / "exclude").write_text(
                "generated/\n", encoding="utf-8"
            )
            model_pack = aios / "generated" / "modelpack"
            model_payload = model_pack / "assets" / "fixture-model.bin"
            model_payload.parent.mkdir(parents=True)
            model_payload.write_bytes(b"fixture model")
            model_manifest = model_pack / "model_artifacts.json"
            model_manifest.write_text(json.dumps({
                "schema_version": 1,
                "artifacts": [{
                    "model_id": "fixture-model",
                    "relative_path": "models/fixture-model.bin",
                    "size_bytes": model_payload.stat().st_size,
                    "sha256": hashlib.sha256(model_payload.read_bytes()).hexdigest(),
                }],
            }), encoding="utf-8")

            runtime_pack = aios / "generated" / "runtimepack" / "litert_lm"
            unsigned = runtime_pack / "assets" / "aios-runtime-litert_lm.apk"
            unsigned.parent.mkdir(parents=True)
            unsigned.write_bytes(b"unsigned provider")
            runtime_manifest = runtime_pack / "runtime_artifacts.json"
            runtime_manifest.write_text(json.dumps({
                "schema_version": 1,
                "runtime": "litert_lm",
                "source_revision": "a" * 40,
                "provider_apk": {
                    "relative_path": "runtime/aios-runtime-litert_lm.apk",
                    "size_bytes": unsigned.stat().st_size,
                    "sha256": hashlib.sha256(unsigned.read_bytes()).hexdigest(),
                },
            }), encoding="utf-8")

            installed_path = product_out / "installed-files-system.json"
            installed = json.loads(installed_path.read_text(encoding="utf-8"))

            def stage(relative, payload):
                target = product_out / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(payload)
                installed.append({
                    "Name": "/" + relative,
                    "Size": target.stat().st_size,
                    "SHA256": hashlib.sha256(target.read_bytes()).hexdigest(),
                })

            stage("system/product/etc/aios/models/fixture-model.bin",
                  model_payload.read_bytes())
            stage("system/product/etc/aios/model_artifacts.json",
                  model_manifest.read_bytes())
            stage("system/product/etc/aios/runtime_artifacts-litert_lm.json",
                  runtime_manifest.read_bytes())
            stage("system/product/priv-app/AiosRuntimeProvider_litert_lm/"
                  "AiosRuntimeProvider_litert_lm.apk", b"platform signed provider")
            installed_path.write_text(json.dumps(installed), encoding="utf-8")

            value = evidence.capture(
                aios, "android_gsi_arm64", manifest, lock, out, log
            )
            self.assertEqual(
                ["fixture-model"],
                value["generated_payloads"]["model_pack"]["models"],
            )
            runtime = value["generated_payloads"]["runtime_packs"][0]
            self.assertEqual("litert_lm", runtime["runtime"])
            self.assertNotEqual(runtime["unsigned_provider_sha256"],
                                runtime["platform_signed_provider_sha256"])
            paths = {item["path"] for item in value["artifacts"]}
            self.assertIn(
                "system/product/etc/aios/models/fixture-model.bin", paths
            )
            self.assertIn(
                "system/product/priv-app/AiosRuntimeProvider_litert_lm/"
                "AiosRuntimeProvider_litert_lm.apk", paths
            )

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
