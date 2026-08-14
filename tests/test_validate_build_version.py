import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "validate_build_version", ROOT / "tools" / "validate_build_version.py"
)
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


class BuildVersionTests(unittest.TestCase):
    def policy(self):
        return validator.select_policy(ROOT, "pixel9a_tegu_hardware")

    def test_accepts_newer_date_bound_version(self):
        value = validator.validate(self.policy(), "2026081401", "1786749300")
        self.assertEqual("2026-08-14", value["utc_date"])
        self.assertEqual(1786749300, value["build_datetime"])

    def test_rejects_base_reuse_and_non_numeric_versions(self):
        with self.assertRaisesRegex(validator.VersionError, "newer"):
            validator.validate(self.policy(), "2026081300", "1786646737")
        with self.assertRaisesRegex(validator.VersionError, "YYYYMMDDNN"):
            validator.validate(self.policy(), "eng.latest", "1786749300")

    def test_rejects_mismatched_utc_date(self):
        with self.assertRaisesRegex(validator.VersionError, "UTC timestamp date"):
            validator.validate(self.policy(), "2026081501", "1786749300")


if __name__ == "__main__":
    unittest.main()
