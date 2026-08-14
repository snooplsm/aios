import base64
import hashlib
import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "package_pixel_ota", ROOT / "tools" / "package_pixel_ota.py"
)
packager = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(packager)


class PixelOtaPackagingTests(unittest.TestCase):
    def lane(self):
        return packager.select_lane(ROOT, "pixel9a_tegu_hardware")

    def build_evidence(self, target_files):
        lane = self.lane()
        digest = hashlib.sha256(target_files.read_bytes()).hexdigest()
        return {
            "schema_version": 2,
            "status": "passed",
            "lane": lane["id"],
            "product": lane["product"],
            "target_device": lane["target_device"],
            "artifact_layout": "full_device_target_files",
            "installed_files_sha256": digest,
            "target_files_package": {
                "size_bytes": target_files.stat().st_size,
                "sha256": digest,
            },
            "build_fingerprint": (
                "AIOS/aios_tegu/tegu:17/FIXTURE/2026081300:userdebug/test-keys"
            ),
            "security_patch": "2026-08-05",
            "generated_payloads": {
                "model_pack": {"models": list(lane["required_model_ids"])},
                "runtime_packs": [
                    {"runtime": runtime} for runtime in lane["required_runtime_ids"]
                ],
            },
        }

    def make_target_files(self, path, *, virtual_ab="true", omit_partition=None):
        lane = self.lane()
        partitions = {
            image.removesuffix(".img") for image in lane["required_images"]
        }
        partitions.update({"abl", "init_boot", "system_ext"})
        if omit_partition is not None:
            partitions.remove(omit_partition)
        misc = (
            "ab_update=true\n"
            "use_dynamic_partitions=true\n"
            f"virtual_ab={virtual_ab}\n"
            "virtual_ab_compression=true\n"
            "avb_enable=true\n"
            "default_system_dev_certificate=build/make/target/product/security/testkey\n"
            "building_oem_image=\n"
        )
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(packager.TARGET_METADATA, misc)
            archive.writestr(packager.AB_PARTITIONS, "\n".join(sorted(partitions)) + "\n")

    def make_ota(self, path, evidence, *, file_hash=None, ota_type="AB"):
        payload = b"CrAU" + bytes(range(256)) * 8
        metadata_size = 80
        whole_hash = hashlib.sha256(payload).digest()
        metadata_hash = hashlib.sha256(payload[:metadata_size]).digest()
        properties = (
            f"FILE_HASH={file_hash or base64.b64encode(whole_hash).decode()}\n"
            f"FILE_SIZE={len(payload)}\n"
            f"METADATA_HASH={base64.b64encode(metadata_hash).decode()}\n"
            f"METADATA_SIZE={metadata_size}\n"
        )
        metadata = (
            f"ota-type={ota_type}\n"
            "pre-device=tegu\n"
            f"post-build={evidence['build_fingerprint']}\n"
            "post-build-incremental=2026081300\n"
            f"post-security-patch-level={evidence['security_patch']}\n"
        )
        entries = {
            packager.PAYLOAD: payload,
            packager.PAYLOAD_PROPERTIES: properties.encode(),
            packager.OTA_METADATA: metadata.encode(),
            "META-INF/CERT.RSA": b"fixture certificate",
            "META-INF/CERT.SF": b"fixture signature",
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
        }
        with zipfile.ZipFile(path, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
            signature = b"fixture-pkcs7-signature"
            signature_start = len(signature) + 6
            comment_size = signature_start
            archive.comment = (
                signature
                + signature_start.to_bytes(2, "little")
                + b"\xff\xff"
                + comment_size.to_bytes(2, "little")
            )

    def test_validates_target_files_and_ota_payload_contract(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "target-files.zip"
            self.make_target_files(target_files)
            evidence = self.build_evidence(target_files)
            packager.validate_build_input(self.lane(), evidence, target_files)
            target = packager.inspect_target_files(target_files, self.lane())
            self.assertIn("product", target["ab_partitions"])
            ota = Path(raw) / "ota.zip"
            self.make_ota(ota, evidence)
            result = packager.inspect_ota_zip(ota, self.lane(), evidence)
            self.assertEqual("AB", result["ota_metadata"]["ota-type"])
            self.assertGreater(result["payload"]["size_bytes"], 0)
            self.assertGreater(
                result["whole_file_signature"]["signature_size_bytes"], 0
            )

    def test_rejects_non_virtual_ab_and_missing_partition(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "non-virtual.zip"
            self.make_target_files(target_files, virtual_ab="false")
            with self.assertRaisesRegex(packager.PackageError, "virtual_ab=true"):
                packager.inspect_target_files(target_files, self.lane())
            missing = Path(raw) / "missing.zip"
            self.make_target_files(missing, omit_partition="vendor_boot")
            with self.assertRaisesRegex(packager.PackageError, "required A/B partitions"):
                packager.inspect_target_files(missing, self.lane())

    def test_rejects_payload_hash_and_metadata_mismatch(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "target-files.zip"
            self.make_target_files(target_files)
            evidence = self.build_evidence(target_files)
            bad_hash = base64.b64encode(b"x" * 32).decode()
            ota = Path(raw) / "bad-hash.zip"
            self.make_ota(ota, evidence, file_hash=bad_hash)
            with self.assertRaisesRegex(packager.PackageError, "FILE_HASH"):
                packager.inspect_ota_zip(ota, self.lane(), evidence)
            wrong_type = Path(raw) / "wrong-type.zip"
            self.make_ota(wrong_type, evidence, ota_type="BLOCK")
            with self.assertRaisesRegex(packager.PackageError, "A/B package"):
                packager.inspect_ota_zip(wrong_type, self.lane(), evidence)

    def test_python_releasetool_uses_the_running_interpreter(self):
        tool = Path("/aosp/build/make/tools/releasetools/ota_from_target_files.py")
        target_files = Path("/evidence/target-files.zip")
        output = Path("/evidence/ota.zip")
        self.assertEqual(
            [sys.executable, str(tool), str(target_files), str(output)],
            packager.ota_tool_command(tool, target_files, output),
        )
        binary = tool.with_suffix("")
        self.assertEqual(
            [str(binary), str(target_files), str(output)],
            packager.ota_tool_command(binary, target_files, output),
        )
        checker = Path("/aosp/out/host/linux-x86/bin/check_ota_package_signature")
        certificate = Path("/aosp/build/make/target/product/security/testkey.x509.pem")
        self.assertEqual(
            [str(checker), str(certificate), str(output)],
            packager.signature_checker_command(checker, certificate, output),
        )


if __name__ == "__main__":
    unittest.main()
