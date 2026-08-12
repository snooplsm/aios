import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "record_model_acceptance", ROOT / "tools" / "record_model_acceptance.py"
)
acceptance = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(acceptance)


def write_catalog(path):
    path.write_text(json.dumps({
        "schema_version": 1,
        "models": [
            {"id": "model-a", "license_url": "https://example.invalid/a"},
            {"id": "model-b", "license_url": "https://example.invalid/b"},
        ],
    }), encoding="utf-8")


class ModelAcceptanceTests(unittest.TestCase):
    def locations(self, temporary):
        source = temporary / "source"
        source.mkdir()
        catalog = source / "catalog.json"
        write_catalog(catalog)
        return source, catalog, temporary / "private" / "acceptance.json"

    def test_records_multiple_exact_catalog_acceptances(self):
        with tempfile.TemporaryDirectory() as raw:
            source, catalog, output = self.locations(Path(raw))
            result = acceptance.record_acceptance(
                catalog, output, "test-builder", [
                    ("model-a", "https://example.invalid/a"),
                    ("model-b", "https://example.invalid/b"),
                ], "2026-08-12T12:00:00Z", source)

            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(["model-a", "model-b"], result["added"])
            self.assertEqual(2, result["record_count"])
            self.assertEqual("test-builder", document["accepted"][0]["accepted_by"])
            self.assertEqual("2026-08-12T12:00:00Z",
                             document["accepted"][1]["accepted_at"])

    def test_is_idempotent_and_preserves_original_acceptance(self):
        with tempfile.TemporaryDirectory() as raw:
            source, catalog, output = self.locations(Path(raw))
            request = [("model-a", "https://example.invalid/a")]
            acceptance.record_acceptance(
                catalog, output, "first", request, "2026-01-01T00:00:00Z", source)
            result = acceptance.record_acceptance(
                catalog, output, "second", request, "2026-08-12T00:00:00Z", source)

            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual([], result["added"])
            self.assertEqual("first", document["accepted"][0]["accepted_by"])
            self.assertEqual("2026-01-01T00:00:00Z",
                             document["accepted"][0]["accepted_at"])

    def test_rejects_wrong_license_unknown_model_and_duplicate_request(self):
        with tempfile.TemporaryDirectory() as raw:
            source, catalog, output = self.locations(Path(raw))
            with self.assertRaisesRegex(acceptance.AcceptanceError, "URL mismatch"):
                acceptance.record_acceptance(
                    catalog, output, "builder",
                    [("model-a", "https://example.invalid/wrong")], source_root=source)
            with self.assertRaisesRegex(acceptance.AcceptanceError, "unknown"):
                acceptance.record_acceptance(
                    catalog, output, "builder",
                    [("model-c", "https://example.invalid/c")], source_root=source)
            with self.assertRaisesRegex(acceptance.AcceptanceError, "duplicate"):
                acceptance.record_acceptance(
                    catalog, output, "builder", [
                        ("model-a", "https://example.invalid/a"),
                        ("model-a", "https://example.invalid/a"),
                    ], source_root=source)

    def test_rejects_output_inside_source_tree(self):
        with tempfile.TemporaryDirectory() as raw:
            source, catalog, _ = self.locations(Path(raw))
            with self.assertRaisesRegex(acceptance.AcceptanceError, "outside"):
                acceptance.record_acceptance(
                    catalog, source / "acceptance.json", "builder",
                    [("model-a", "https://example.invalid/a")], source_root=source)

    def test_rejects_malformed_existing_record_without_overwrite(self):
        with tempfile.TemporaryDirectory() as raw:
            source, catalog, output = self.locations(Path(raw))
            output.parent.mkdir()
            output.write_text('{"schema_version": 9}', encoding="utf-8")
            before = output.read_bytes()

            with self.assertRaisesRegex(acceptance.AcceptanceError, "unsupported"):
                acceptance.record_acceptance(
                    catalog, output, "builder",
                    [("model-a", "https://example.invalid/a")], source_root=source)

            self.assertEqual(before, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
