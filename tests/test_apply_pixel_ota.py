import base64
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "apply_pixel_ota", ROOT / "tools" / "apply_pixel_ota.py"
)
updater = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(updater)


class FakeRunner:
    def __init__(self, values):
        self.values = values
        self.calls = []

    def run(self, arguments, **kwargs):
        self.calls.append((arguments, kwargs))
        key = tuple(arguments)
        if key not in self.values:
            raise AssertionError(f"unexpected command: {arguments}")
        return self.values[key]


class ApplyRunner:
    def __init__(self, *, verify_output="Payload is applicable.",
                 allocation_output="Successfully allocated space for payload."):
        self.verify_output = verify_output
        self.allocation_output = allocation_output
        self.calls = []

    def run(self, arguments, **kwargs):
        self.calls.append((arguments, kwargs))
        if arguments[:1] in (["root"], ["wait-for-device"], ["push"]):
            return ""
        if (arguments[:3] in (
                ["shell", "mkdir", "-p"],
                ["shell", "chown", "system:cache"],
                ["shell", "chmod", "0640"],
                ["shell", "rm", "-f"],
            ) or arguments[:2] == ["shell", "restorecon"]):
            return ""
        if arguments[0] == "shell" and len(arguments) == 2:
            command = arguments[1]
            if "--verify" in command:
                return self.verify_output
            if "--allocate" in command:
                return self.allocation_output
            if "--update" in command:
                return "onPayloadApplicationComplete(ErrorCode::kSuccess (0))"
        raise AssertionError(f"unexpected command: {arguments}")


class PixelOtaUpdateTests(unittest.TestCase):
    def make_ota(self, path, *, timestamp=1786646738, incremental="2026081401"):
        payload = b"CrAU" + bytes(range(128)) * 4
        payload_hash = hashlib.sha256(payload).digest()
        payload_metadata = payload[:16]
        metadata_hash = hashlib.sha256(payload_metadata).digest()
        properties = (
            f"FILE_HASH={base64.b64encode(payload_hash).decode()}\n"
            f"FILE_SIZE={len(payload)}\n"
            "METADATA_HASH=" + base64.b64encode(metadata_hash).decode() + "\n"
            f"METADATA_SIZE={len(payload_metadata)}\n"
        )
        metadata_values = {
            "ota-type": "AB",
            "post-build": (
                "AIOS/aios_tegu/tegu:17/FIXTURE/"
                f"{incremental}:userdebug/test-keys"
            ),
            "post-build-incremental": incremental,
            "post-security-patch-level": "2026-08-05",
            "post-timestamp": str(timestamp),
            "pre-device": "tegu",
        }
        metadata = "".join(
            f"{key}={value}\n" for key, value in sorted(metadata_values.items())
        )
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(updater.OTA_METADATA, metadata, compress_type=zipfile.ZIP_STORED)
            archive.writestr(
                updater.PAYLOAD_PROPERTIES,
                properties,
                compress_type=zipfile.ZIP_STORED,
            )
            archive.writestr(updater.PAYLOAD, payload, compress_type=zipfile.ZIP_STORED)
        with path.open("rb") as stream, zipfile.ZipFile(stream) as archive:
            offset = updater.zip_data_offset(stream, archive.getinfo(updater.PAYLOAD))
        metadata_values["ota-property-files"] = f"payload.bin:{offset}:{len(payload)}"
        metadata = "".join(
            f"{key}={value}\n" for key, value in sorted(metadata_values.items())
        )
        # Rewriting metadata can alter the payload offset. Reserve an exact-width
        # placeholder, then replace it once the stable local-header layout exists.
        for _ in range(3):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(
                    updater.OTA_METADATA, metadata, compress_type=zipfile.ZIP_STORED
                )
                archive.writestr(
                    updater.PAYLOAD_PROPERTIES,
                    properties,
                    compress_type=zipfile.ZIP_STORED,
                )
                archive.writestr(
                    updater.PAYLOAD, payload, compress_type=zipfile.ZIP_STORED
                )
            with path.open("rb") as stream, zipfile.ZipFile(stream) as archive:
                offset = updater.zip_data_offset(stream, archive.getinfo(updater.PAYLOAD))
            next_value = f"payload.bin:{offset}:{len(payload)}"
            if metadata_values["ota-property-files"] == next_value:
                break
            metadata_values["ota-property-files"] = next_value
            metadata = "".join(
                f"{key}={value}\n" for key, value in sorted(metadata_values.items())
            )
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        evidence = {
            "schema_version": 1,
            "status": "passed",
            "update_kind": "full_virtual_ab_ota",
            "lane": "pixel9a_tegu_hardware",
            "product": "aios_tegu",
            "target_device": "tegu",
            "signing_state": "public_android_test_keys_unlocked_bootloader_only",
            "contains_required_model_payloads": True,
            "installation_performed": False,
            "signature_verification": {
                "status": "passed",
                "whole_file_and_payload_verified": True,
            },
            "ota_archive": {
                "size_bytes": path.stat().st_size,
                "sha256": digest,
            },
            "payload": {
                "size_bytes": len(payload),
                "sha256": payload_hash.hex(),
                "metadata_size_bytes": len(payload_metadata),
                "metadata_sha256": metadata_hash.hex(),
            },
            "ota_metadata": metadata_values,
            "ota_metadata_sha256": hashlib.sha256(metadata.encode()).hexdigest(),
            "payload_properties_sha256": hashlib.sha256(properties.encode()).hexdigest(),
            "build_fingerprint": metadata_values["post-build"],
            "security_patch": metadata_values["post-security-patch-level"],
        }
        return evidence

    def device_values(self, *, fingerprint, timestamp="1786646737", free="10000000"):
        properties = {
            "sys.boot_completed": "1",
            "ro.product.device": "tegu",
            "ro.build.fingerprint": fingerprint,
            "ro.build.version.incremental": "2026081300",
            "ro.build.date.utc": timestamp,
            "ro.build.version.security_patch": "2026-08-05",
            "ro.build.tags": "test-keys",
            "ro.boot.slot_suffix": "_a",
            "ro.boot.flash.locked": "0",
            "ro.boot.verifiedbootstate": "orange",
            "ro.boot.vbmeta.device_state": "unlocked",
            "ro.virtual_ab.enabled": "true",
            "ro.virtual_ab.compression.enabled": "true",
            "ro.build.ab_update": "true",
            "ro.aios.version": "0.1-dev",
        }
        values = {
            ("devices", "-l"): "SERIAL device product:aios_tegu\nemulator-5554 device",
            ("shell", "command", "-v", "update_engine_client"):
                "/system/bin/update_engine_client",
            ("shell", "update_engine_client", "--help"):
                " ".join(updater.REQUIRED_UPDATE_ENGINE_FLAGS),
            ("shell", "service", "check", "android.os.UpdateEngineService"):
                "Service android.os.UpdateEngineService: found",
            ("shell", "df", "-k", "/data"):
                f"Filesystem 1K-blocks Used Available Use% Mounted\n/dev/dm 1 1 {free} 1% /data",
        }
        values.update({
            ("shell", "getprop", name): value for name, value in properties.items()
        })
        return values

    def test_verifies_evidence_zip_offsets_and_newer_device_target(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive)
            evidence_path = Path(raw) / "evidence.json"
            evidence_path.write_text(json.dumps(evidence))
            ota = updater.verify_ota_input(evidence, evidence_path, archive)
            source = "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
            runner = FakeRunner(self.device_values(fingerprint=source))
            device = updater.inspect_device(runner, "SERIAL", ota)
            self.assertTrue(device["install_eligible"])
            self.assertEqual("_b", device["expected_target_slot"])

    def test_same_build_is_read_only_but_not_install_eligible(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive, timestamp=1786646737)
            evidence_path = Path(raw) / "evidence.json"
            evidence_path.write_text(json.dumps(evidence))
            ota = updater.verify_ota_input(evidence, evidence_path, archive)
            runner = FakeRunner(self.device_values(
                fingerprint=ota["target_fingerprint"], timestamp="1786646737"
            ))
            device = updater.inspect_device(runner, "SERIAL", ota)
            self.assertFalse(device["install_eligible"])
            self.assertEqual(
                ["same_build", "target_not_newer"], device["ineligibility_reasons"]
            )
            with self.assertRaisesRegex(updater.UpdateError, "not eligible"):
                updater.apply_update(
                    runner, ota, archive, "SERIAL", device, "anything"
                )

    def test_rejects_tampering_bad_stream_range_and_wrong_device(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive)
            evidence_path = Path(raw) / "evidence.json"
            evidence_path.write_text(json.dumps(evidence))
            bad = json.loads(json.dumps(evidence))
            bad["ota_archive"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(updater.UpdateError, "does not match"):
                updater.verify_ota_input(bad, evidence_path, archive)
            bad = json.loads(json.dumps(evidence))
            bad["ota_metadata"]["ota-property-files"] = "payload.bin:1:2"
            with self.assertRaisesRegex(updater.UpdateError, "differs"):
                updater.verify_ota_input(bad, evidence_path, archive)
            ota = updater.verify_ota_input(evidence, evidence_path, archive)
            values = self.device_values(fingerprint="not-aios")
            runner = FakeRunner(values)
            with self.assertRaisesRegex(updater.UpdateError, "full-device product"):
                updater.inspect_device(runner, "SERIAL", ota)

    def test_requires_serial_bound_confirmation(self):
        with self.assertRaisesRegex(updater.UpdateError, "APPLY-SERIAL-TO-2026081401"):
            updater.require_update_confirmation("SERIAL", "2026081401", None)
        updater.require_update_confirmation(
            "SERIAL", "2026081401", "APPLY-SERIAL-TO-2026081401"
        )

    def test_extracts_exact_evidenced_payload_metadata(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive)
            output = Path(raw) / "metadata.bin"
            updater.copy_payload_metadata(
                archive,
                output,
                evidence["payload"]["metadata_size_bytes"],
                evidence["payload"]["metadata_sha256"],
            )
            with zipfile.ZipFile(archive) as ota, ota.open(updater.PAYLOAD) as payload:
                expected = payload.read(evidence["payload"]["metadata_size_bytes"])
            self.assertEqual(expected, output.read_bytes())

    def test_semantic_update_engine_results_fail_closed(self):
        updater.require_update_engine_applicable("Payload is applicable.")
        updater.require_update_engine_allocation(
            "Successfully allocated space for payload."
        )
        with self.assertRaisesRegex(updater.UpdateError, "rejected"):
            updater.require_update_engine_applicable("Payload is not applicable.")
        with self.assertRaisesRegex(updater.UpdateError, "allocate"):
            updater.require_update_engine_allocation(
                "Insufficient space; required 123 bytes."
            )

    def test_apply_verifies_and_allocates_before_update_then_cleans_staging(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive)
            evidence_path = Path(raw) / "evidence.json"
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            ota = updater.verify_ota_input(evidence, evidence_path, archive)
            device = {
                "install_eligible": True,
                "ineligibility_reasons": [],
                "source_fingerprint": (
                    "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
                ),
                "source_slot": "_a",
                "expected_target_slot": "_b",
            }
            runner = ApplyRunner()
            result = updater.apply_update(
                runner,
                ota,
                archive,
                "SERIAL",
                device,
                "APPLY-SERIAL-TO-2026081401",
            )
            remote_commands = [
                call[0][1] for call in runner.calls
                if call[0][0] == "shell" and len(call[0]) == 2
            ]
            self.assertLess(
                next(i for i, command in enumerate(remote_commands)
                     if "--verify" in command),
                next(i for i, command in enumerate(remote_commands)
                     if "--allocate" in command),
            )
            self.assertLess(
                next(i for i, command in enumerate(remote_commands)
                     if "--allocate" in command),
                next(i for i, command in enumerate(remote_commands)
                     if "--update" in command),
            )
            self.assertTrue(result["payload_applicability_verified"])
            self.assertTrue(result["payload_space_allocated"])
            self.assertTrue(result["staging_removed"])
            self.assertEqual("rm", runner.calls[-1][0][1])

    def test_apply_rejects_not_applicable_payload_and_cleans_staging(self):
        with tempfile.TemporaryDirectory() as raw:
            archive = Path(raw) / "ota.zip"
            evidence = self.make_ota(archive)
            evidence_path = Path(raw) / "evidence.json"
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            ota = updater.verify_ota_input(evidence, evidence_path, archive)
            runner = ApplyRunner(verify_output="Payload is not applicable.")
            with self.assertRaisesRegex(updater.UpdateError, "rejected"):
                updater.apply_update(
                    runner,
                    ota,
                    archive,
                    "SERIAL",
                    {
                        "install_eligible": True,
                        "ineligibility_reasons": [],
                        "source_fingerprint": "source",
                        "source_slot": "_a",
                        "expected_target_slot": "_b",
                    },
                    "APPLY-SERIAL-TO-2026081401",
                )
            self.assertEqual("rm", runner.calls[-1][0][1])
            self.assertFalse(any(
                call[0][0] == "shell" and len(call[0]) == 2
                and "--update" in call[0][1]
                for call in runner.calls
            ))

    def test_refuses_evidence_inside_source_tree(self):
        with self.assertRaisesRegex(updater.UpdateError, "outside source"):
            updater.write_json_atomic(ROOT / "never-write-ota-evidence.json", {})


if __name__ == "__main__":
    unittest.main()
