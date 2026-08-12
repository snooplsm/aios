import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "check_aosp_manifest", ROOT / "tools" / "check_aosp_manifest.py"
)
checker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(checker)


COMMIT_A = "a" * 40
COMMIT_B = "b" * 40
COMMIT_C = "c" * 40
COMMIT_D = "d" * 40
COMMIT_E = "e" * 40


def manifest(projects):
    rows = ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<manifest>"]
    rows.extend(
        f'  <project name="{name}" path="{path}" revision="{revision}" />'
        for name, path, revision in projects
    )
    rows.append("</manifest>")
    return "\n".join(rows)


class AospManifestContractTests(unittest.TestCase):
    def write_manifest(self, directory, projects):
        path = Path(directory) / "resolved.xml"
        path.write_text(manifest(projects), encoding="utf-8")
        return path

    def integration_projects(self):
        return [
            ("platform/build", "build/make", COMMIT_A),
            ("device/google/cuttlefish", "device/google/cuttlefish", COMMIT_B),
            ("platform/packages/apps/Dialer", "packages/apps/Dialer", COMMIT_C),
            ("platform/frameworks/base", "frameworks/base", COMMIT_E),
            ("aios", "vendor/aios", COMMIT_D),
        ]

    def avd_projects(self):
        return self.integration_projects() + [
            ("device/generic/goldfish", "device/generic/goldfish", COMMIT_A),
        ]

    def gsi_projects(self):
        return self.integration_projects() + [
            ("device/generic/common", "device/generic/common", COMMIT_A),
        ]

    def test_android_latest_integration_lane_accepts_resolved_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            value = checker.check(
                path, ROOT, "android_latest_integration", COMMIT_E)
            self.assertEqual("aios_cf_x86_64_phone", value["product"])
            self.assertEqual(COMMIT_D, value["aios_revision"])
            self.assertFalse(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertEqual(COMMIT_E, value["manifest_repository_revision"])

    def test_android_avd_lane_accepts_goldfish_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.avd_projects())
            value = checker.check(
                path, ROOT, "android_avd_integration", COMMIT_E)
            self.assertEqual("aios_sdk_phone_x86_64", value["product"])
            self.assertEqual("virtual_emulator", value["kind"])
            self.assertFalse(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])

    def test_android_avd_lane_rejects_manifest_without_goldfish(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            with self.assertRaisesRegex(checker.ManifestContractError,
                                        "device/generic/goldfish"):
                checker.check(path, ROOT, "android_avd_integration", COMMIT_E)

    def test_android_gsi_lane_accepts_generic_common_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.gsi_projects())
            value = checker.check(path, ROOT, "android_gsi_arm64", COMMIT_E)
            self.assertEqual("aios_gsi_arm64", value["product"])
            self.assertEqual("generic_system_image", value["kind"])
            self.assertTrue(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])

    def test_android_gsi_lane_rejects_manifest_without_generic_common(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            with self.assertRaisesRegex(checker.ManifestContractError,
                                        "device/generic/common"):
                checker.check(path, ROOT, "android_gsi_arm64", COMMIT_E)

    def test_pixel_lane_rejects_manifest_without_tegu(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            with self.assertRaisesRegex(checker.ManifestContractError,
                                        "device/google/tegu"):
                checker.check(path, ROOT, "pixel9a_tegu_hardware", COMMIT_E)

    def test_symbolic_project_revision_is_not_a_release_lock(self):
        with tempfile.TemporaryDirectory() as raw:
            projects = self.integration_projects()
            projects[0] = (projects[0][0], projects[0][1], "android17-release")
            path = self.write_manifest(raw, projects)
            with self.assertRaisesRegex(checker.ManifestContractError, "immutable"):
                checker.check(path, ROOT, "android_latest_integration", COMMIT_E)

    def test_lock_is_atomic_and_refuses_accidental_overwrite(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            output = Path(raw) / "manifest-lock.json"
            checker.check(
                path, ROOT, "android_latest_integration", COMMIT_E, output)
            value = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(5, value["project_count"])
            with self.assertRaisesRegex(checker.ManifestContractError, "overwrite"):
                checker.check(
                    path, ROOT, "android_latest_integration", COMMIT_E, output)

    def test_manifest_repository_revision_must_be_an_exact_commit(self):
        with tempfile.TemporaryDirectory() as raw:
            path = self.write_manifest(raw, self.integration_projects())
            with self.assertRaisesRegex(checker.ManifestContractError, "not immutable"):
                checker.check(
                    path, ROOT, "android_latest_integration", "android-latest-release")


if __name__ == "__main__":
    unittest.main()
