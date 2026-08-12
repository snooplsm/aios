import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "capture_cuttlefish_boot_evidence",
    ROOT / "tools" / "capture_cuttlefish_boot_evidence.py",
)
capture_tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(capture_tool)


FINGERPRINT = "aios/vsoc_x86_64/test:userdebug/test-keys"
REVISION = "a" * 40
BOOT_ID = "12345678-1234-4abc-8def-1234567890ab"


def build_evidence():
    return {
        "schema_version": 2,
        "status": "passed",
        "lane": "android_latest_integration",
        "kind": "virtual_integration",
        "product": "aios_cf_x86_64_phone",
        "target_device": "vsoc_x86_64",
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
        ("shell", "getprop", "sys.boot_completed"): "1",
        ("shell", "getprop", "ro.aios.version"): "0.1-dev",
        ("shell", "getprop", "ro.product.name"): "aios_cf_x86_64_phone",
        ("shell", "getprop", "ro.product.device"): "vsoc_x86_64",
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
    for action, package_name, component in capture_tool.EXPECTED_SERVICES:
        values[(
            "shell", "cmd", "package", "query-services", "--brief",
            "--components", "--user", "0", "-a", action, "-p", package_name,
        )] = component
    return values


class CuttlefishBootEvidenceTests(unittest.TestCase):
    def write_build_evidence(self, directory, value=None):
        path = Path(directory) / "build-evidence.json"
        path.write_text(json.dumps(value or build_evidence()), encoding="utf-8")
        return path

    def test_captures_digest_bound_first_boot_and_services(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            output = Path(raw) / "cuttlefish-boot.json"
            observed = responses()
            value = capture_tool.capture(
                build_path,
                "0.0.0.0:6520",
                output,
                query=lambda _serial, *arguments: observed[arguments],
            )

            self.assertEqual("passed", value["status"])
            self.assertEqual("integration.android_latest_first_boot", value["gate"])
            self.assertEqual(BOOT_ID, value["boot_id"])
            self.assertEqual(FINGERPRINT, value["build_fingerprint"])
            self.assertEqual(
                hashlib.sha256(build_path.read_bytes()).hexdigest(),
                value["build_evidence_sha256"],
            )
            self.assertEqual(
                len(capture_tool.EXPECTED_SERVICES), len(value["resolved_services"])
            )
            self.assertFalse(value["lane_eligible_for_physical_gates"])
            self.assertFalse(value["proves_physical_runtime_gate"])
            self.assertEqual(value, json.loads(output.read_text(encoding="utf-8")))

    def test_refuses_non_local_serial_before_querying_adb(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)

            def unexpected_query(_serial, *_arguments):
                self.fail("ADB must not be queried for a non-local serial")

            with self.assertRaisesRegex(
                    capture_tool.CuttlefishBootEvidenceError, "non-local"):
                capture_tool.capture(
                    build_path, "55201JEBF12498", query=unexpected_query
                )

    def test_rejects_running_image_from_another_build(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "getprop", "ro.build.fingerprint")] = "other/build"
            with self.assertRaisesRegex(
                    capture_tool.CuttlefishBootEvidenceError, "fingerprint"):
                capture_tool.capture(
                    build_path,
                    "127.0.0.1:6520",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_non_cuttlefish_device(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            observed[("shell", "getprop", "ro.product.device")] = "tegu"
            with self.assertRaisesRegex(
                    capture_tool.CuttlefishBootEvidenceError, "ro.product.device"):
                capture_tool.capture(
                    build_path,
                    "localhost:6520",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_missing_required_service(self):
        with tempfile.TemporaryDirectory() as raw:
            build_path = self.write_build_evidence(raw)
            observed = responses()
            action, package_name, _component = capture_tool.EXPECTED_SERVICES[0]
            observed[(
                "shell", "cmd", "package", "query-services", "--brief",
                "--components", "--user", "0", "-a", action, "-p", package_name,
            )] = "No service found"
            with self.assertRaisesRegex(
                    capture_tool.CuttlefishBootEvidenceError, action):
                capture_tool.capture(
                    build_path,
                    "0.0.0.0:6520",
                    query=lambda _serial, *arguments: observed[arguments],
                )

    def test_rejects_build_evidence_that_can_claim_physical_gates(self):
        with tempfile.TemporaryDirectory() as raw:
            build = build_evidence()
            build["lane_eligible_for_physical_gates"] = True
            build_path = self.write_build_evidence(raw, build)
            with self.assertRaisesRegex(
                    capture_tool.CuttlefishBootEvidenceError, "physical"):
                capture_tool.capture(
                    build_path, "0.0.0.0:6520", query=lambda *_: ""
                )


if __name__ == "__main__":
    unittest.main()
