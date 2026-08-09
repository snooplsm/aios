#!/usr/bin/env python3
"""Conservative lossless AIOS XMP insertion for simple JPEG containers."""

from __future__ import annotations

from dataclasses import dataclass


XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
AIOS_NAMESPACE = b"https://aios.dev/ns/media/1.0/"
FORBIDDEN_FEATURES = (
    b"hdrgm:",
    b"http://ns.adobe.com/hdr-gain-map",
    b"GContainer",
    b"Container:Directory",
    b"MicroVideo",
    b"MotionPhoto",
    b"Camera:MotionPhoto",
)


class UnsafeJpeg(ValueError):
    pass


@dataclass(frozen=True)
class Inspection:
    insertion_offset: int
    aios_xmp_count: int


def _segment_end(data: bytes, length_offset: int) -> int:
    if length_offset + 2 > len(data):
        raise UnsafeJpeg("truncated JPEG segment length")
    length = int.from_bytes(data[length_offset:length_offset + 2], "big")
    if length < 2:
        raise UnsafeJpeg("invalid JPEG segment length")
    end = length_offset + length
    if end > len(data):
        raise UnsafeJpeg("truncated JPEG segment")
    return end


def _inspect_app(marker: int, payload: bytes, allow_aios_xmp: bool) -> int:
    if any(feature in payload for feature in FORBIDDEN_FEATURES):
        raise UnsafeJpeg("advanced or offset-bearing photo feature detected")
    if marker == 0xE0:
        if not (payload.startswith(b"JFIF\x00") or payload.startswith(b"JFXX\x00")):
            raise UnsafeJpeg("unknown APP0 payload")
        return 0
    if marker == 0xE1:
        if payload.startswith(b"Exif\x00\x00"):
            return 0
        if payload.startswith(XMP_HEADER):
            packet = payload[len(XMP_HEADER):]
            if allow_aios_xmp and AIOS_NAMESPACE in packet:
                return 1
            raise UnsafeJpeg("existing non-AIOS or duplicate XMP packet")
        raise UnsafeJpeg("unknown APP1 payload")
    if marker == 0xE2:
        if payload.startswith(b"MPF\x00"):
            raise UnsafeJpeg("multi-picture JPEG/MPF is not writable")
        if not payload.startswith(b"ICC_PROFILE\x00"):
            raise UnsafeJpeg("unknown APP2 payload")
        return 0
    if marker == 0xEE:
        if not payload.startswith(b"Adobe"):
            raise UnsafeJpeg("unknown APP14 payload")
        return 0
    raise UnsafeJpeg(f"unsupported APP marker: 0x{marker:02x}")


def inspect(data: bytes, *, allow_aios_xmp: bool = False) -> Inspection:
    if len(data) < 4 or data[:2] != b"\xff\xd8":
        raise UnsafeJpeg("missing JPEG SOI")
    position = 2
    insertion_offset = 2
    header_metadata = True
    aios_xmp_count = 0
    eoi_offset: int | None = None

    while position < len(data):
        if data[position] != 0xFF:
            raise UnsafeJpeg("expected JPEG marker")
        marker_start = position
        while position < len(data) and data[position] == 0xFF:
            position += 1
        if position >= len(data):
            raise UnsafeJpeg("truncated JPEG marker")
        marker = data[position]
        position += 1
        if marker == 0x00:
            raise UnsafeJpeg("stuffed byte outside entropy scan")
        if marker == 0xD8:
            raise UnsafeJpeg("nested JPEG SOI")
        if marker == 0xD9:
            eoi_offset = position
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            header_metadata = False
            continue

        end = _segment_end(data, position)
        payload = data[position + 2:end]
        if 0xE0 <= marker <= 0xEF:
            aios_xmp_count += _inspect_app(marker, payload, allow_aios_xmp)
        if header_metadata and (0xE0 <= marker <= 0xEF or marker == 0xFE):
            insertion_offset = end
        else:
            header_metadata = False

        if marker != 0xDA:
            position = end
            continue

        scan = end
        while scan < len(data):
            next_ff = data.find(b"\xff", scan)
            if next_ff < 0 or next_ff + 1 >= len(data):
                raise UnsafeJpeg("unterminated JPEG entropy scan")
            following = data[next_ff + 1]
            if following == 0x00 or 0xD0 <= following <= 0xD7:
                scan = next_ff + 2
                continue
            if following == 0xFF:
                scan = next_ff + 1
                continue
            position = next_ff
            break
        else:
            raise UnsafeJpeg("unterminated JPEG entropy scan")

    if eoi_offset is None:
        raise UnsafeJpeg("missing JPEG EOI")
    if eoi_offset != len(data):
        raise UnsafeJpeg("appended payload after JPEG EOI")
    if allow_aios_xmp and aios_xmp_count != 1:
        raise UnsafeJpeg("candidate must contain exactly one AIOS XMP packet")
    if not allow_aios_xmp and aios_xmp_count != 0:
        raise UnsafeJpeg("source already contains AIOS XMP")
    return Inspection(insertion_offset, aios_xmp_count)


def inject_aios_xmp(original: bytes, xmp_packet: bytes) -> bytes:
    source = inspect(original, allow_aios_xmp=False)
    if AIOS_NAMESPACE not in xmp_packet:
        raise UnsafeJpeg("XMP packet does not use the AIOS namespace")
    payload = XMP_HEADER + xmp_packet
    length = len(payload) + 2
    if length > 0xFFFF:
        raise UnsafeJpeg("XMP packet exceeds JPEG APP1 capacity")
    segment = b"\xff\xe1" + length.to_bytes(2, "big") + payload
    candidate = (
        original[:source.insertion_offset]
        + segment
        + original[source.insertion_offset:]
    )
    inspect(candidate, allow_aios_xmp=True)
    restored = (
        candidate[:source.insertion_offset]
        + candidate[source.insertion_offset + len(segment):]
    )
    if restored != original:
        raise UnsafeJpeg("lossless byte-preservation check failed")
    return candidate
