import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_patch_series", ROOT / "tools" / "verify_patch_series.py"
)
patches = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(patches)


def git(checkout, *arguments, text=True):
    result = subprocess.run(
        ["git", "-c", "core.autocrlf=false", *arguments],
        cwd=checkout,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )
    return result.stdout.strip() if text else result.stdout


class PatchSeriesTransactionTests(unittest.TestCase):
    def create_fixture(self, raw):
        base = Path(raw)
        aosp = base / "aosp"
        checkout = aosp / "packages" / "apps" / "Dialer"
        checkout.mkdir(parents=True)
        git(checkout, "init")
        git(checkout, "config", "core.autocrlf", "false")
        git(checkout, "config", "user.name", "AIOS Test")
        git(checkout, "config", "user.email", "test@aios.invalid")
        source = checkout / "example.txt"
        source.write_text("before\n", encoding="utf-8", newline="\n")
        git(checkout, "add", "example.txt")
        git(checkout, "commit", "-m", "base")
        revision = git(checkout, "rev-parse", "HEAD")

        source.write_text("after\n", encoding="utf-8", newline="\n")
        patch_bytes = git(checkout, "diff", "--binary", text=False)
        git(checkout, "restore", "example.txt")

        aios = base / "aios"
        patch_dir = aios / "patches"
        patch_dir.mkdir(parents=True)
        patch_path = patch_dir / "change.patch"
        patch_path.write_bytes(patch_bytes)
        (patch_dir / "series.json").write_text(json.dumps({
            "schema_version": 1,
            "patches": [{
                "id": "test-change",
                "project": "packages/apps/Dialer",
                "file": "change.patch",
                "base_revision": revision,
                "sha256": hashlib.sha256(patch_bytes).hexdigest(),
            }],
        }), encoding="utf-8")
        return aios, aosp, checkout, source

    def test_apply_stages_and_revert_restores_exact_base(self):
        with tempfile.TemporaryDirectory() as raw:
            root, aosp, checkout, source = self.create_fixture(raw)
            patches.verify(root, aosp, {}, reverse=False)
            patches.apply_series(root, aosp, {})
            self.assertEqual("after\n", source.read_text(encoding="utf-8"))
            self.assertEqual("M  example.txt", git(checkout, "status", "--short"))
            patches.verify(root, aosp, {}, reverse=True)
            patches.revert_series(root, aosp, {})
            self.assertEqual("before\n", source.read_text(encoding="utf-8"))
            self.assertEqual("", git(checkout, "status", "--porcelain"))

    def test_apply_refuses_dirty_tracked_checkout(self):
        with tempfile.TemporaryDirectory() as raw:
            root, aosp, _, source = self.create_fixture(raw)
            source.write_text("owner change\n", encoding="utf-8", newline="\n")
            with self.assertRaisesRegex(patches.PatchVerificationError, "dirty"):
                patches.apply_series(root, aosp, {})

    def test_revert_refuses_unstaged_edits_and_preserves_patch(self):
        with tempfile.TemporaryDirectory() as raw:
            root, aosp, _, source = self.create_fixture(raw)
            patches.apply_series(root, aosp, {})
            source.write_text("additional edit\n", encoding="utf-8", newline="\n")
            with self.assertRaisesRegex(patches.PatchVerificationError, "unstaged"):
                patches.revert_series(root, aosp, {})
            self.assertEqual("additional edit\n", source.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
