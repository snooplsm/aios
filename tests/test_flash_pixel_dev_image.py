import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "flash_pixel_dev_image", ROOT / "tools" / "flash_pixel_dev_image.py"
)
flasher = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(flasher)


SERIAL = "PIXEL9AFIXTURE"


class FakeRunner:
    def __init__(self, overrides=None, serials=None):
        self.values = {
            "product": "tegu",
            "unlocked": "yes",
            "version-bootloader": "tegu-fixture",
            "version-baseband": "radio-fixture",
            "is-userspace": "no",
        }
        self.values.update(overrides or {})
        self.serials = serials if serials is not None else [SERIAL]
        self.commands = []

    def run(self, arguments, *, serial=True):
        self.commands.append((arguments, serial))
        if arguments[:2] == ["devices", "-l"]:
            return "".join(f"{value}\tfastboot usb:1\n" for value in self.serials)
        if arguments and arguments[0] == "getvar":
            key = arguments[1]
            return f"(bootloader) {key}: {self.values[key]}\nFinished.\n"
        if "update" in arguments:
            return "Finished. Total time: 1.0s\n"
        raise AssertionError(arguments)


class PixelDevFlashTests(unittest.TestCase):
    def evidence(self):
        return {
            "target_device": "tegu",
            "requirements": {
                "version-bootloader": "tegu-fixture",
                "version-baseband": "radio-fixture",
            },
        }

    def test_preflight_accepts_exact_single_unlocked_tegu(self):
        state = flasher.preflight(FakeRunner(), self.evidence(), SERIAL)
        self.assertEqual("tegu", state["product"])
        self.assertEqual("yes", state["unlocked"])

    def test_preflight_rejects_wrong_firmware_locked_or_multiple_devices(self):
        cases = (
            (FakeRunner({"version-bootloader": "wrong"}), "version-bootloader"),
            (FakeRunner({"version-baseband": "wrong"}), "version-baseband"),
            (FakeRunner({"unlocked": "no"}), "unlocked"),
            (FakeRunner(serials=[SERIAL, "OTHER"]), "exactly fastboot device"),
            (FakeRunner({"is-userspace": "yes"}), "bootloader fastboot"),
        )
        for runner, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(
                    flasher.FlashError, message):
                flasher.preflight(runner, self.evidence(), SERIAL)

    def test_execute_requires_serial_bound_wipe_confirmation(self):
        with self.assertRaisesRegex(flasher.FlashError, "confirm-wipe"):
            flasher.require_wipe_confirmation(SERIAL, "wrong")
        flasher.require_wipe_confirmation(SERIAL, f"ERASE-{SERIAL}-FOR-AIOS")

    def test_parse_getvar_rejects_missing_or_ambiguous_value(self):
        with self.assertRaisesRegex(flasher.FlashError, "unambiguous product"):
            flasher.parse_getvar("Finished.\n", "product")
        with self.assertRaisesRegex(flasher.FlashError, "unambiguous product"):
            flasher.parse_getvar("product: tegu\nproduct: akita\n", "product")

    def test_flash_result_is_atomic_and_serial_is_hashed(self):
        with tempfile.TemporaryDirectory() as raw:
            output = Path(raw) / "flash-result.json"
            value = {
                "serial_sha256": flasher.text_sha256(SERIAL),
                "proves_flash_command_passed": True,
            }
            flasher.write_json_atomic(output, value)
            stored = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(64, len(stored["serial_sha256"]))
            self.assertNotIn(SERIAL, output.read_text(encoding="utf-8"))
            with self.assertRaisesRegex(flasher.FlashError, "overwrite"):
                flasher.write_json_atomic(output, value)


if __name__ == "__main__":
    unittest.main()
