import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_model_pack_evidence", ROOT / "tools" / "capture_model_pack_evidence.py"
)
evidence = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(evidence)


def git(checkout, *arguments):
    result = subprocess.run(
        ["git", "-c", "core.autocrlf=false", *arguments], cwd=checkout,
        check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.stdout.strip()


class ModelPackEvidenceTests(unittest.TestCase):
    def create_fixture(self, raw):
        base = Path(raw)
        checkout = base / "aios"
        (checkout / "config").mkdir(parents=True)
        pack = base / "pack"
        assets = pack / "assets"
        assets.mkdir(parents=True)
        weights = b"verified-model-weights"
        weights_path = assets / "model-a.litertlm"
        weights_path.write_bytes(weights)
        digest = hashlib.sha256(weights).hexdigest()
        license_bytes = b"fixture license\n"
        license_digest = hashlib.sha256(license_bytes).hexdigest()
        models = []
        artifacts = []
        for model_id, capabilities in (
                ("model-a", ["text_generation"]),
                ("model-b", ["image_understanding"])):
            license_dir = assets / model_id
            license_dir.mkdir()
            (license_dir / "LICENSE.txt").write_bytes(license_bytes)
            models.append({
                "id": model_id,
                "runtime": "fixture_runtime",
                "capabilities": capabilities,
                "languages": ["en", "es"],
                "allowed_backends": ["cpu"],
                "artifact_formats": ["litertlm"],
                "license_url": "https://example.invalid/license",
                "reference_artifact": {
                    "sha256": digest,
                    "size_bytes": len(weights),
                },
                "packaged_license": {
                    "filename": "LICENSE.txt",
                    "sha256": license_digest,
                    "size_bytes": len(license_bytes),
                    "soong_license_kinds": ["SPDX-license-identifier-Apache-2.0"],
                },
            })
            artifacts.append({
                "model_id": model_id,
                "artifact_format": "litertlm",
                "relative_path": "models/model-a.litertlm",
                "sha256": digest,
                "size_bytes": len(weights),
                "runtime": "fixture_runtime",
                "backend": "cpu",
                "capabilities": capabilities,
                "languages": ["en", "es"],
                "license_url": "https://example.invalid/license",
                "packaged_license": {
                    "filename": "LICENSE.txt",
                    "relative_path": f"models/{model_id}/LICENSE.txt",
                    "sha256": license_digest,
                    "size_bytes": len(license_bytes),
                    "license_url": "https://example.invalid/license",
                    "soong_license_kinds": ["SPDX-license-identifier-Apache-2.0"],
                    "soong_license_module": (
                        f"aios_model_{model_id.replace('-', '_')}_model_license_terms"),
                },
            })
        catalog = checkout / "config" / "model_catalog.json"
        catalog.write_text(json.dumps({
            "schema_version": 1, "models": models,
        }), encoding="utf-8")
        (pack / "model_artifacts.json").write_text(json.dumps({
            "schema_version": 1, "artifacts": artifacts,
        }), encoding="utf-8")
        git(checkout, "init")
        git(checkout, "config", "user.name", "AIOS Test")
        git(checkout, "config", "user.email", "test@aios.invalid")
        git(checkout, "add", "config/model_catalog.json")
        git(checkout, "commit", "-m", "fixture")
        return checkout, pack

    def test_captures_catalog_bound_deduplicated_pack_without_weights(self):
        with tempfile.TemporaryDirectory() as raw:
            checkout, pack = self.create_fixture(raw)
            output = Path(raw) / "evidence.json"

            result = evidence.capture(
                checkout, pack, output, "2026-08-12T12:00:00+00:00")

            self.assertEqual("passed", result["status"])
            self.assertEqual(2, result["logical_artifact_count"])
            self.assertEqual(1, result["physical_model_payload_count"])
            self.assertFalse(result["contains_model_weights"])
            self.assertFalse(result["proves_model_inference"])
            self.assertRegex(result["aios_revision"], r"^[0-9a-f]{40}$")
            self.assertTrue(output.is_file())
            self.assertNotIn("verified-model-weights", output.read_text(encoding="utf-8"))

    def test_rejects_catalog_drift(self):
        with tempfile.TemporaryDirectory() as raw:
            checkout, pack = self.create_fixture(raw)
            manifest_path = pack / "model_artifacts.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["artifacts"][0]["runtime"] = "wrong_runtime"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(
                    evidence.ModelPackEvidenceError, "catalog field mismatch"):
                evidence.capture(checkout, pack)

    def test_rejects_dirty_checkout_and_existing_output(self):
        with tempfile.TemporaryDirectory() as raw:
            checkout, pack = self.create_fixture(raw)
            (checkout / "untracked.txt").write_text("dirty", encoding="utf-8")
            with self.assertRaisesRegex(
                    evidence.ModelPackEvidenceError, "must be clean"):
                evidence.capture(checkout, pack)

            (checkout / "untracked.txt").unlink()
            output = Path(raw) / "evidence.json"
            output.write_text("keep", encoding="utf-8")
            with self.assertRaisesRegex(
                    evidence.ModelPackEvidenceError, "refusing to overwrite"):
                evidence.capture(checkout, pack, output)
            self.assertEqual("keep", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
