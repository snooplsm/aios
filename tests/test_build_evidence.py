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
    def create_fixture(self, raw):
        base = Path(raw)
        aios = base / "aios"
        (aios / "config").mkdir(parents=True)
        (aios / "patches").mkdir(parents=True)
        shutil.copy(ROOT / "config" / "aosp_lanes.json",
                    aios / "config" / "aosp_lanes.json")
        shutil.copy(ROOT / "patches" / "series.json",
                    aios / "patches" / "series.json")
        git(aios, "init")
        git(aios, "config", "core.autocrlf", "false")
        git(aios, "config", "user.name", "AIOS Test")
        git(aios, "config", "user.email", "test@aios.invalid")
        git(aios, "add", "config/aosp_lanes.json", "patches/series.json")
        git(aios, "commit", "-m", "fixture")
        head = git(aios, "rev-parse", "HEAD")

        manifest = base / "aosp-manifest.xml"
        manifest.write_text("<manifest><project name=\"aios\" /></manifest>\n",
                            encoding="utf-8")
        manifest_lock = base / "aosp-manifest-lock.json"
        manifest_lock.write_text(json.dumps({
            "schema_version": 1,
            "lane": "android_latest_integration",
            "product": "aios_cf_x86_64_phone",
            "aios_revision": head,
            "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        }), encoding="utf-8")

        out = base / "out"
        product_out = out / "target" / "product" / "vsoc_x86_64"
        (product_out / "product").mkdir(parents=True)
        (product_out / "system").mkdir(parents=True)
        (product_out / "product" / "build.prop").write_text(
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
            self.assertEqual("aios/test/fingerprint", value["build_fingerprint"])
            self.assertEqual(12, len(value["artifacts"]))
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertTrue(output.is_file())

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


if __name__ == "__main__":
    unittest.main()
