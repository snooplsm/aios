import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "bootstrap_reference_model", ROOT / "tools" / "bootstrap_reference_model.py"
)
bootstrap = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bootstrap)


class FakeResponse(io.BytesIO):
    def __init__(self, payload, status=200, headers=None):
        super().__init__(payload)
        self.status = status
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


def write_catalog(path, payload, model_id="fixture-model"):
    path.write_text(json.dumps({
        "schema_version": 1,
        "models": [{
            "id": model_id,
            "reference_artifact": {
                "url": "https://example.invalid/fixture-model.litertlm",
                "size_bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            },
        }],
    }), encoding="utf-8")


class ReferenceModelBootstrapTests(unittest.TestCase):
    def locations(self, temporary):
        source = temporary / "source"
        source.mkdir()
        output = temporary / "models"
        catalog = temporary / "catalog.json"
        return source, output, catalog

    def test_downloads_and_atomically_publishes_verified_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            payload = b"catalog-pinned-model-bytes"
            write_catalog(catalog, payload)
            requests = []

            def opener(request, timeout):
                requests.append((request, timeout))
                return FakeResponse(payload)

            result = bootstrap.download_reference(
                catalog, "fixture-model", output, opener, source)

            self.assertTrue(result["downloaded"])
            self.assertEqual(payload, Path(result["path"]).read_bytes())
            self.assertEqual(60, requests[0][1])
            self.assertIsNone(requests[0][0].get_header("Range"))
            self.assertFalse((output / "fixture-model.litertlm.partial").exists())
            self.assertFalse((output / "fixture-model.litertlm.download.lock").exists())

    def test_resumes_partial_download_with_valid_content_range(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            payload = b"complete-model-payload"
            write_catalog(catalog, payload)
            output.mkdir()
            partial = output / "fixture-model.litertlm.partial"
            partial.write_bytes(payload[:8])

            def opener(request, timeout):
                self.assertEqual("bytes=8-", request.get_header("Range"))
                return FakeResponse(
                    payload[8:], 206,
                    {"Content-Range": f"bytes 8-{len(payload) - 1}/{len(payload)}"},
                )

            result = bootstrap.download_reference(
                catalog, "fixture-model", output, opener, source)

            self.assertEqual(payload, Path(result["path"]).read_bytes())

    def test_full_response_replaces_partial_when_server_ignores_range(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            payload = b"complete-model-payload"
            write_catalog(catalog, payload)
            output.mkdir()
            (output / "fixture-model.litertlm.partial").write_bytes(b"stale")

            result = bootstrap.download_reference(
                catalog, "fixture-model", output,
                lambda request, timeout: FakeResponse(payload, 200), source)

            self.assertEqual(payload, Path(result["path"]).read_bytes())

    def test_existing_matching_artifact_avoids_network(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            payload = b"already-downloaded-model"
            write_catalog(catalog, payload)
            output.mkdir()
            destination = output / "fixture-model.litertlm"
            destination.write_bytes(payload)

            def forbidden_opener(request, timeout):
                raise AssertionError("network should not be used")

            result = bootstrap.download_reference(
                catalog, "fixture-model", output, forbidden_opener, source)

            self.assertFalse(result["downloaded"])
            self.assertEqual(destination, Path(result["path"]))

    def test_digest_mismatch_preserves_partial_but_never_publishes(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            write_catalog(catalog, b"expected-model")

            with self.assertRaisesRegex(bootstrap.BootstrapError, "digest mismatch"):
                bootstrap.download_reference(
                    catalog, "fixture-model", output,
                    lambda request, timeout: FakeResponse(b"tampered-model"), source)

            self.assertTrue((output / "fixture-model.litertlm.partial").is_file())
            self.assertFalse((output / "fixture-model.litertlm").exists())
            self.assertFalse((output / "fixture-model.litertlm.download.lock").exists())

    def test_rejects_source_tree_output_and_models_without_references(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source = temporary / "source"
            source.mkdir()
            catalog = temporary / "catalog.json"
            write_catalog(catalog, b"model")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "outside"):
                bootstrap.download_reference(
                    catalog, "fixture-model", source / "models",
                    lambda request, timeout: FakeResponse(b"model"), source)

            catalog.write_text(json.dumps({
                "schema_version": 1,
                "models": [{"id": "fixture-model"}],
            }), encoding="utf-8")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "no single-file"):
                bootstrap.load_reference(catalog, "fixture-model")

    def test_rejects_invalid_resume_range(self):
        with tempfile.TemporaryDirectory() as raw:
            temporary = Path(raw)
            source, output, catalog = self.locations(temporary)
            payload = b"complete-model-payload"
            write_catalog(catalog, payload)
            output.mkdir()
            partial = output / "fixture-model.litertlm.partial"
            partial.write_bytes(payload[:8])

            with self.assertRaisesRegex(bootstrap.BootstrapError, "invalid resume"):
                bootstrap.download_reference(
                    catalog, "fixture-model", output,
                    lambda request, timeout: FakeResponse(
                        payload[8:], 206, {"Content-Range": "bytes 9-21/22"}),
                    source,
                )

            self.assertEqual(payload[:8], partial.read_bytes())


if __name__ == "__main__":
    unittest.main()
