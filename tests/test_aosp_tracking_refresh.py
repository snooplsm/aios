import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "refresh_aosp_tracking", ROOT / "tools" / "refresh_aosp_tracking.py"
)
tracking = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(tracking)


def git(repository, *arguments):
    result = subprocess.run(
        ["git", "-c", "core.autocrlf=false", *arguments],
        cwd=repository,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout.strip()


class AospTrackingRefreshTests(unittest.TestCase):
    def create_fixture(self, raw, release="android17-release"):
        base = Path(raw)
        root = base / "aios"
        (root / "config").mkdir(parents=True)
        policy = {
            "schema_version": 1,
            "manifest_url": tracking.OFFICIAL_MANIFEST_URL,
            "tracking_revision": "android-latest-release",
            "observed_release_branch": "android16-release",
            "observed_release_manifest_commit": "a" * 40,
            "observed_on": "2026-01-01",
            "release_manifest_lock": None,
            "first_device": {"codename": "tegu"},
        }
        policy_path = root / "config" / "aosp_tracking.json"
        policy_path.write_text(json.dumps(policy), encoding="utf-8")

        manifest = base / "manifest"
        manifest.mkdir()
        git(manifest, "init")
        git(manifest, "config", "user.name", "AIOS Test")
        git(manifest, "config", "user.email", "test@aios.invalid")
        git(manifest, "remote", "add", "origin", tracking.OFFICIAL_MANIFEST_URL)
        (manifest / "default.xml").write_text(
            f'<manifest><default revision="{release}" remote="aosp" /></manifest>\n',
            encoding="utf-8",
        )
        git(manifest, "add", "default.xml")
        git(manifest, "commit", "-m", "manifest fixture")
        commit = git(manifest, "rev-parse", "HEAD")
        git(manifest, "update-ref",
            "refs/remotes/origin/android-latest-release", commit)
        return root, manifest, policy_path, commit

    def test_write_records_exact_tracking_commit_and_resolved_release(self):
        with tempfile.TemporaryDirectory() as raw:
            root, manifest, path, commit = self.create_fixture(raw)
            value = tracking.run(root, manifest, True, "2026-08-10")
            persisted = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(commit, value["observed_release_manifest_commit"])
            self.assertEqual("android17-release", value["observed_release_branch"])
            self.assertEqual("2026-08-10", persisted["observed_on"])
            self.assertIsNone(persisted["release_manifest_lock"])

    def test_check_fails_until_reviewed_tracking_value_matches(self):
        with tempfile.TemporaryDirectory() as raw:
            root, manifest, _, _ = self.create_fixture(raw)
            with self.assertRaises(tracking.TrackingOutOfDate):
                tracking.run(root, manifest, False)
            tracking.run(root, manifest, True, "2026-08-10")
            tracking.run(root, manifest, False)

    def test_nonofficial_manifest_remote_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            root, manifest, _, _ = self.create_fixture(raw)
            git(manifest, "remote", "set-url", "origin", "https://example.invalid/manifest")
            with self.assertRaisesRegex(tracking.TrackingContractError, "not official"):
                tracking.run(root, manifest, False)

    def test_checkout_must_equal_the_remote_tracking_ref(self):
        with tempfile.TemporaryDirectory() as raw:
            root, manifest, _, _ = self.create_fixture(raw)
            (manifest / "default.xml").write_text(
                '<manifest><default revision="android17-release" /></manifest>\n',
                encoding="utf-8",
            )
            git(manifest, "add", "default.xml")
            git(manifest, "commit", "-m", "untracked local manifest commit")
            with self.assertRaisesRegex(tracking.TrackingContractError, "rerun repo init"):
                tracking.run(root, manifest, False)

    def test_symbolic_or_preview_default_revision_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            root, manifest, _, _ = self.create_fixture(raw, "main")
            with self.assertRaisesRegex(tracking.TrackingContractError, "numbered release"):
                tracking.run(root, manifest, False)


if __name__ == "__main__":
    unittest.main()
