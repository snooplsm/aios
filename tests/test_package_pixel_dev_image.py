import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "package_pixel_dev_image", ROOT / "tools" / "package_pixel_dev_image.py"
)
packager = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(packager)


class PixelDevImagePackagingTests(unittest.TestCase):
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
            "generated_payloads": {
                "model_pack": {"models": list(lane["required_model_ids"])},
                "runtime_packs": [
                    {"runtime": runtime}
                    for runtime in lane["required_runtime_ids"]
                ],
            },
        }

    def make_fastboot_zip(self, path, *, omit=None, board="tegu"):
        lane = self.lane()
        entries = {
            "android-info.txt": (
                f"require board={board}\n"
                "require version-bootloader=tegu-fixture\n"
                "require version-baseband=radio-fixture\n"
            ).encode(),
            "fastboot-info.txt": (
                "version 1\n"
                "flash boot\n"
                "flash vendor_boot\n"
                "flash --apply-vbmeta vbmeta\n"
                "reboot fastboot\n"
                "update-super\n"
                "flash system\n"
                "flash product\n"
                "flash vendor\n"
            ).encode(),
        }
        entries.update({image: f"fixture:{image}".encode()
                        for image in lane["required_images"]})
        if omit is not None:
            entries.pop(omit)
        with zipfile.ZipFile(path, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)

    def test_validates_build_input_and_fastboot_contract(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "target-files.zip"
            target_files.write_bytes(b"target files")
            evidence = self.build_evidence(target_files)
            packager.validate_build_input(self.lane(), evidence, target_files)
            image = Path(raw) / "image.zip"
            self.make_fastboot_zip(image)
            value = packager.inspect_fastboot_zip(image, self.lane())
            self.assertEqual("tegu", value["requirements"]["board"])
            self.assertEqual(7, len(value["required_images"]))

    def test_rejects_model_free_build_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "target-files.zip"
            target_files.write_bytes(b"target files")
            evidence = self.build_evidence(target_files)
            evidence.pop("generated_payloads")
            with self.assertRaisesRegex(packager.PackageError, "model set"):
                packager.validate_build_input(self.lane(), evidence, target_files)

    def test_rejects_wrong_board_and_missing_image(self):
        with tempfile.TemporaryDirectory() as raw:
            wrong_board = Path(raw) / "wrong-board.zip"
            self.make_fastboot_zip(wrong_board, board="akita")
            with self.assertRaisesRegex(packager.PackageError, "lane device"):
                packager.inspect_fastboot_zip(wrong_board, self.lane())
            missing = Path(raw) / "missing.zip"
            self.make_fastboot_zip(missing, omit="vendor_boot.img")
            with self.assertRaisesRegex(packager.PackageError, "required images"):
                packager.inspect_fastboot_zip(missing, self.lane())

    def test_rejects_target_files_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as raw:
            target_files = Path(raw) / "target-files.zip"
            target_files.write_bytes(b"target files")
            evidence = self.build_evidence(target_files)
            evidence["target_files_package"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(packager.PackageError, "does not match"):
                packager.validate_build_input(self.lane(), evidence, target_files)


if __name__ == "__main__":
    unittest.main()
