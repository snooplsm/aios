import struct
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "assets" / "bootanimation" / "bootanimation.zip"


class BootAnimationTests(unittest.TestCase):
    def test_animation_is_stored_and_matches_pixel_9a_canvas(self):
        with zipfile.ZipFile(ARCHIVE) as archive:
            names = archive.namelist()
            self.assertEqual(
                "1080 2424 30\np 1 0 part0\np 0 0 part1\n",
                archive.read("desc.txt").decode("ascii"),
            )
            self.assertEqual(12, len([name for name in names if name.startswith("part0/")]))
            self.assertEqual(["part1/00000.png"], [
                name for name in names if name.startswith("part1/")
            ])
            for info in archive.infolist():
                self.assertEqual(zipfile.ZIP_STORED, info.compress_type)
                if not info.filename.endswith(".png"):
                    continue
                data = archive.read(info)
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
                width, height = struct.unpack(">II", data[16:24])
                self.assertEqual((1080, 2424), (width, height))


if __name__ == "__main__":
    unittest.main()
