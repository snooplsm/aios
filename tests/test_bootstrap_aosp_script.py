import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "bootstrap-aosp.sh"


class BootstrapAospScriptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SCRIPT.read_text(encoding="utf-8")

    def test_virtual_lanes_stay_on_official_latest_aosp(self):
        self.assertIn(
            'manifest_url="https://android.googlesource.com/platform/manifest"',
            self.text,
        )
        self.assertIn('revision="android-latest-release"', self.text)

    def test_pixel_lane_is_pinned_to_verified_grapheneos_manifest(self):
        self.assertIn(
            'manifest_url="https://github.com/GrapheneOS/platform_manifest.git"',
            self.text,
        )
        self.assertIn('revision="refs/tags/$revision"', self.text)
        self.assertIn("verify-tag", self.text)
        self.assertIn(
            "d1b2739828a783bbf9bd6ba5d50c727b9329b9b7",
            self.text,
        )

    def test_pixel_lane_generates_full_device_support(self):
        self.assertIn("adevtool generate-all -d tegu", self.text)
        self.assertIn("full tegu target-files/factory images", self.text)
        self.assertIn("Do not reuse the", self.text)
        self.assertIn("generic GSI flashing procedure", self.text)


if __name__ == "__main__":
    unittest.main()
