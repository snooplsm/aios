import unittest

from tools.jpeg_xmp import AIOS_NAMESPACE, UnsafeJpeg, inject_aios_xmp, inspect


def segment(marker, payload):
    length = len(payload) + 2
    return b"\xff" + bytes([marker]) + length.to_bytes(2, "big") + payload


def simple_jpeg(*, extra_header=b"", entropy=b"\x11\xff\x00\x22\xff\xd0\x33"):
    return (
        b"\xff\xd8"
        + segment(0xE0, b"JFIF\x00" + b"\x01\x02\x00\x00\x01\x00\x01\x00\x00")
        + segment(0xE1, b"Exif\x00\x00MM\x00*")
        + extra_header
        + segment(0xDB, b"\x00" + bytes(range(1, 65)))
        + segment(0xDA, b"\x01\x01\x00\x00\x3f\x00")
        + entropy
        + b"\xff\xd9"
    )


XMP = (
    b"<?xpacket?><rdf:RDF><rdf:Description xmlns:aios='"
    + AIOS_NAMESPACE
    + b"' aios:schemaVersion='1'/></rdf:RDF>"
)


class JpegXmpTests(unittest.TestCase):
    def test_inserts_one_packet_without_changing_original_bytes(self):
        original = simple_jpeg()
        source = inspect(original)
        candidate = inject_aios_xmp(original, XMP)
        result = inspect(candidate, allow_aios_xmp=True)
        self.assertEqual(1, result.aios_xmp_count)
        self.assertGreater(len(candidate), len(original))
        self.assertEqual(original[:source.insertion_offset],
                         candidate[:source.insertion_offset])
        self.assertTrue(candidate.endswith(original[source.insertion_offset:]))

    def test_handles_progressive_style_multiple_scans(self):
        entropy = (
            b"\x01\xff\x00\x02"
            + segment(0xC4, b"\x00\x01\x02")
            + segment(0xDA, b"\x01\x01\x00\x00\x3f\x00")
            + b"\x03\xff\xd1\x04"
        )
        candidate = inject_aios_xmp(simple_jpeg(entropy=entropy), XMP)
        self.assertEqual(1, inspect(candidate, allow_aios_xmp=True).aios_xmp_count)

    def test_rejects_existing_xmp(self):
        existing = segment(0xE1, b"http://ns.adobe.com/xap/1.0/\x00<old/>")
        with self.assertRaisesRegex(UnsafeJpeg, "existing"):
            inject_aios_xmp(simple_jpeg(extra_header=existing), XMP)

    def test_rejects_mpf_multi_picture_container(self):
        with self.assertRaisesRegex(UnsafeJpeg, "MPF"):
            inject_aios_xmp(simple_jpeg(extra_header=segment(0xE2, b"MPF\x00data")), XMP)

    def test_rejects_ultra_hdr_markers(self):
        payload = b"Exif\x00\x00hdrgm:Version http://ns.adobe.com/hdr-gain-map/1.0/"
        with self.assertRaisesRegex(UnsafeJpeg, "advanced"):
            inject_aios_xmp(simple_jpeg(extra_header=segment(0xE1, payload)), XMP)

    def test_rejects_motion_photo_or_appended_video(self):
        with self.assertRaisesRegex(UnsafeJpeg, "advanced"):
            inject_aios_xmp(
                simple_jpeg(extra_header=segment(0xE1, b"Exif\x00\x00MotionPhoto")), XMP)
        with self.assertRaisesRegex(UnsafeJpeg, "appended"):
            inject_aios_xmp(simple_jpeg() + b"fake-mp4", XMP)

    def test_rejects_unknown_app_payloads(self):
        with self.assertRaisesRegex(UnsafeJpeg, "unsupported APP"):
            inject_aios_xmp(simple_jpeg(extra_header=segment(0xEC, b"vendor-data")), XMP)

    def test_rejects_oversized_xmp(self):
        oversized = AIOS_NAMESPACE + b"x" * 70_000
        with self.assertRaisesRegex(UnsafeJpeg, "capacity"):
            inject_aios_xmp(simple_jpeg(), oversized)

    def test_rejects_truncated_entropy_scan(self):
        with self.assertRaisesRegex(UnsafeJpeg, "unterminated|missing"):
            inject_aios_xmp(simple_jpeg()[:-2], XMP)


if __name__ == "__main__":
    unittest.main()
