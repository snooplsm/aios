import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_avd_boot_evidence", ROOT / "tools" / "capture_avd_boot_evidence.py"
)
capture_tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(capture_tool)


FINGERPRINT = "aios/sdk_phone_x86_64/test:userdebug/test-keys"
REVISION = "a" * 40
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"


def build_evidence():
    return {
        "schema_version": 2,
        "status": "passed",
        "lane": "android_avd_integration",
        "kind": "virtual_emulator",
        "product": "aios_sdk_phone_x86_64",
        "target_device": "emulator_x86_64",
        "build_fingerprint": FINGERPRINT,
        "aios_revision": REVISION,
        "lane_eligible_for_physical_gates": False,
        "proves_physical_runtime_gate": False,
        "artifacts": [
            {"path": "product.img", "size_bytes": 10, "sha256": "b" * 64},
            {"path": "system.img", "size_bytes": 20, "sha256": "c" * 64},
        ],
    }


def responses():
    values = {
        ("get-state",): "device",
        ("shell", "getprop", "ro.kernel.qemu"): "1",
        ("shell", "getprop", "sys.boot_completed"): "1",
        ("shell", "getprop", "ro.aios.version"): "0.1-dev",
        ("shell", "getprop", "ro.product.name"): "aios_sdk_phone_x86_64",
        ("shell", "getprop", "ro.build.type"): "userdebug",
        ("shell", "getprop", "ro.debuggable"): "1",
        ("shell", "getprop", "ro.build.fingerprint"): FINGERPRINT,
        ("shell", "cat", "/proc/sys/kernel/random/boot_id"): BOOT_ID,
        ("shell", "cat", "/proc/uptime"): "42.25 12.50",
    }
    for package_name in capture_tool.EXPECTED_PACKAGES:
        values[("shell", "pm", "path", package_name)] = (
            f"package:/product/priv-app/Test/{package_name}.apk"
        )
    return values


class AvdBootEvidenceTests(unittest.TestCase):
    def write_build_evidence(self, directory, value=None):
        path = Path(directory) / "build-evidence.json"
        path.write_text(json.dumps(value or build_evidence()), encoding="utf-8")
        return path

    def test_captures_digest_bound_first_boot(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            output = Path(raw) / "avd-boot.json"
            observed = responses()

            value = capture_tool.capture(
                build_path,
                "emulator-5580",
                output,
                query=lambda _serial, *arguments: observed[arguments],
            )

            self.assertEqual("passed", value["status"])
            self.assertEqual("integration.android_avd_first_boot", value["gate"])
            self.assertEqual(BOOT_ID, value["boot_id"])
            self.assertEqual(42.25, value["uptime_seconds"])
            self.assertEqual(FINGERPRINT, value["build_fingerprint"])
            self.assertEqual(
                hashlib.sha256(build_path.read_bytes()).hexdigest(),
                value["build_evidence_sha256"],
            )
            self.assertEqual(
                set(capture_tool.EXPECTED_PACKAGES), set(value["packages"])
            )
            self.assertFalse(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertEqual(value, json.loads(output.read_text(encoding="utf-8")))

    def test_refuses_physical_serial_before_querying_adb(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)

            def unexpected_query(_serial, *_arguments):
                self.fail("ADB must not be queried for a physical serial")

            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "non-emulator serial"):
                capture_tool.capture(
                    build_path, "55201JEBF12498", query=unexpected_query
                )

    def test_refuses_emulator_named_serial_without_qemu(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "getprop", "ro.kernel.qemu")] = "0"
            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "ro.kernel.qemu"):
                capture_tool.capture(
                    build_path,
                    "emulator-5580",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_running_image_from_another_build(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "getprop", "ro.build.fingerprint")] = "other/build"
            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "fingerprint"):
                capture_tool.capture(
                    build_path,
                    "emulator-5580",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_missing_product_package(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "pm", "path", "com.aios.phone")] = ""
            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "com.aios.phone"):
                capture_tool.capture(
                    build_path,
                    "emulator-5580",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_non_privileged_package_location(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "pm", "path", "com.aios.phone")] = (
                "package:/product/app/AiosPhone/AiosPhone.apk"
            )
            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "privileged"):
                capture_tool.capture(
                    build_path,
                    "emulator-5580",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_build_evidence_that_can_claim_physical_gates(self):
        with tempfile.TemporaryDirectory() as raw:
            build = build_evidence()
            build["lane_eligible_for_physical_gates"] = True
            build_path = self.write_build_evidence(raw, build)
            with self.assertRaisesRegex(
                    capture_tool.AvdBootEvidenceError, "physical"):
                capture_tool.capture(build_path, "emulator-5580", query=lambda *_: "")


if __name__ == "__main__":
    unittest.main()
