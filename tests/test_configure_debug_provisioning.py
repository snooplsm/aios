import tempfile
import unittest
from pathlib import Path

from tools.configure_debug_provisioning import generate


class DebugProvisioningGeneratorTests(unittest.TestCase):
    def test_generates_escaped_gitignored_overlay(self):
        with tempfile.TemporaryDirectory() as raw:
            output = Path(raw) / "debugprovisioning"
            generate(output, "lab<&", "test-password-123")
            values = (output / "res" / "values" / "config.xml").read_text(
                encoding="utf-8"
            )
            self.assertIn("lab&lt;&amp;", values)
            self.assertIn("test-password-123", values)
            self.assertIn("AiosDebugProvisioningOverlay",
                          (output / "Android.bp").read_text(encoding="utf-8"))
            with self.assertRaises(FileExistsError):
                generate(output, "second", "another-password")

    def test_rejects_invalid_credentials(self):
        with tempfile.TemporaryDirectory() as raw:
            output = Path(raw) / "debugprovisioning"
            with self.assertRaises(ValueError):
                generate(output, "", "test-password")
            with self.assertRaises(ValueError):
                generate(output, "test", "short")


if __name__ == "__main__":
    unittest.main()
