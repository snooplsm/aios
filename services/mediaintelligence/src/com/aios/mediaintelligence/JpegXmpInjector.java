package com.aios.mediaintelligence;

import java.nio.charset.StandardCharsets;

/** Byte-preserving XMP insertion for a deliberately narrow JPEG subset. */
final class JpegXmpInjector {
    private static final byte[] XMP_HEADER =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AIOS_NAMESPACE =
            "https://aios.dev/ns/media/1.0/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] FORBIDDEN_FEATURES = {
            "hdrgm:".getBytes(StandardCharsets.US_ASCII),
            "http://ns.adobe.com/hdr-gain-map".getBytes(StandardCharsets.US_ASCII),
            "GContainer".getBytes(StandardCharsets.US_ASCII),
            "Container:Directory".getBytes(StandardCharsets.US_ASCII),
            "MicroVideo".getBytes(StandardCharsets.US_ASCII),
            "MotionPhoto".getBytes(StandardCharsets.US_ASCII),
            "Camera:MotionPhoto".getBytes(StandardCharsets.US_ASCII),
    };

    static final class UnsafeJpegException extends Exception {
        UnsafeJpegException(String message) {
            super(message);
        }
    }

    private static final class Inspection {
        final int insertionOffset;
        final int aiosXmpCount;

        Inspection(int insertionOffset, int aiosXmpCount) {
            this.insertionOffset = insertionOffset;
            this.aiosXmpCount = aiosXmpCount;
        }
    }

    private JpegXmpInjector() {}

    static byte[] inject(byte[] original, String xmpPacket) throws UnsafeJpegException {
        Inspection source = inspect(original, false);
        byte[] packet = xmpPacket.getBytes(StandardCharsets.UTF_8);
        if (!contains(packet, AIOS_NAMESPACE)) {
            throw new UnsafeJpegException("XMP packet does not use the AIOS namespace");
        }
        int length = XMP_HEADER.length + packet.length + 2;
        if (length > 0xffff) {
            throw new UnsafeJpegException("XMP packet exceeds JPEG APP1 capacity");
        }
        byte[] segment = new byte[length + 2];
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xe1;
        segment[2] = (byte) ((length >>> 8) & 0xff);
        segment[3] = (byte) (length & 0xff);
        System.arraycopy(XMP_HEADER, 0, segment, 4, XMP_HEADER.length);
        System.arraycopy(packet, 0, segment, 4 + XMP_HEADER.length, packet.length);

        byte[] candidate = new byte[original.length + segment.length];
        System.arraycopy(original, 0, candidate, 0, source.insertionOffset);
        System.arraycopy(segment, 0, candidate, source.insertionOffset, segment.length);
        System.arraycopy(
                original,
                source.insertionOffset,
                candidate,
                source.insertionOffset + segment.length,
                original.length - source.insertionOffset);
        inspect(candidate, true);
        if (!regionEquals(candidate, 0, original, 0, source.insertionOffset)
                || !regionEquals(
                candidate,
                source.insertionOffset + segment.length,
                original,
                source.insertionOffset,
                original.length - source.insertionOffset)) {
            throw new UnsafeJpegException("lossless byte-preservation check failed");
        }
        return candidate;
    }

    static boolean containsOneAiosPacket(byte[] candidate) {
        try {
            return inspect(candidate, true).aiosXmpCount == 1;
        } catch (UnsafeJpegException error) {
            return false;
        }
    }

    private static Inspection inspect(byte[] data, boolean allowAiosXmp)
            throws UnsafeJpegException {
        if (data.length < 4 || u8(data[0]) != 0xff || u8(data[1]) != 0xd8) {
            throw new UnsafeJpegException("missing JPEG SOI");
        }
        int position = 2;
        int insertionOffset = 2;
        boolean headerMetadata = true;
        int aiosXmpCount = 0;
        int eoiOffset = -1;

        while (position < data.length) {
            if (u8(data[position]) != 0xff) {
                throw new UnsafeJpegException("expected JPEG marker");
            }
            while (position < data.length && u8(data[position]) == 0xff) {
                position++;
            }
            if (position >= data.length) {
                throw new UnsafeJpegException("truncated JPEG marker");
            }
            int marker = u8(data[position++]);
            if (marker == 0x00) {
                throw new UnsafeJpegException("stuffed byte outside entropy scan");
            }
            if (marker == 0xd8) {
                throw new UnsafeJpegException("nested JPEG SOI");
            }
            if (marker == 0xd9) {
                eoiOffset = position;
                break;
            }
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) {
                headerMetadata = false;
                continue;
            }
            int end = segmentEnd(data, position);
            int payloadStart = position + 2;
            if (marker >= 0xe0 && marker <= 0xef) {
                aiosXmpCount += inspectApp(
                        marker, data, payloadStart, end, allowAiosXmp);
            }
            if (headerMetadata && (marker >= 0xe0 && marker <= 0xef || marker == 0xfe)) {
                insertionOffset = end;
            } else {
                headerMetadata = false;
            }
            if (marker != 0xda) {
                position = end;
                continue;
            }

            int scan = end;
            boolean found = false;
            while (scan < data.length) {
                if (u8(data[scan]) != 0xff) {
                    scan++;
                    continue;
                }
                if (scan + 1 >= data.length) {
                    break;
                }
                int following = u8(data[scan + 1]);
                if (following == 0x00 || following >= 0xd0 && following <= 0xd7) {
                    scan += 2;
                    continue;
                }
                if (following == 0xff) {
                    scan++;
                    continue;
                }
                position = scan;
                found = true;
                break;
            }
            if (!found) {
                throw new UnsafeJpegException("unterminated JPEG entropy scan");
            }
        }
        if (eoiOffset < 0) {
            throw new UnsafeJpegException("missing JPEG EOI");
        }
        if (eoiOffset != data.length) {
            throw new UnsafeJpegException("appended payload after JPEG EOI");
        }
        if (allowAiosXmp && aiosXmpCount != 1) {
            throw new UnsafeJpegException(
                    "candidate must contain exactly one AIOS XMP packet");
        }
        if (!allowAiosXmp && aiosXmpCount != 0) {
            throw new UnsafeJpegException("source already contains AIOS XMP");
        }
        return new Inspection(insertionOffset, aiosXmpCount);
    }

    private static int inspectApp(
            int marker, byte[] data, int start, int end, boolean allowAiosXmp)
            throws UnsafeJpegException {
        for (byte[] feature : FORBIDDEN_FEATURES) {
            if (contains(data, start, end, feature)) {
                throw new UnsafeJpegException(
                        "advanced or offset-bearing photo feature detected");
            }
        }
        if (marker == 0xe0) {
            if (!startsWith(data, start, end, ascii("JFIF\0"))
                    && !startsWith(data, start, end, ascii("JFXX\0"))) {
                throw new UnsafeJpegException("unknown APP0 payload");
            }
            return 0;
        }
        if (marker == 0xe1) {
            if (startsWith(data, start, end, ascii("Exif\0\0"))) {
                return 0;
            }
            if (startsWith(data, start, end, XMP_HEADER)) {
                int packetStart = start + XMP_HEADER.length;
                if (allowAiosXmp && contains(data, packetStart, end, AIOS_NAMESPACE)) {
                    return 1;
                }
                throw new UnsafeJpegException(
                        "existing non-AIOS or duplicate XMP packet");
            }
            throw new UnsafeJpegException("unknown APP1 payload");
        }
        if (marker == 0xe2) {
            if (startsWith(data, start, end, ascii("MPF\0"))) {
                throw new UnsafeJpegException("multi-picture JPEG/MPF is not writable");
            }
            if (!startsWith(data, start, end, ascii("ICC_PROFILE\0"))) {
                throw new UnsafeJpegException("unknown APP2 payload");
            }
            return 0;
        }
        if (marker == 0xee) {
            if (!startsWith(data, start, end, ascii("Adobe"))) {
                throw new UnsafeJpegException("unknown APP14 payload");
            }
            return 0;
        }
        throw new UnsafeJpegException(String.format(
                "unsupported APP marker: 0x%02x", marker));
    }

    private static int segmentEnd(byte[] data, int lengthOffset)
            throws UnsafeJpegException {
        if (lengthOffset + 2 > data.length) {
            throw new UnsafeJpegException("truncated JPEG segment length");
        }
        int length = u8(data[lengthOffset]) << 8 | u8(data[lengthOffset + 1]);
        if (length < 2) {
            throw new UnsafeJpegException("invalid JPEG segment length");
        }
        int end = lengthOffset + length;
        if (end > data.length) {
            throw new UnsafeJpegException("truncated JPEG segment");
        }
        return end;
    }

    private static boolean startsWith(
            byte[] data, int start, int end, byte[] prefix) {
        return end - start >= prefix.length
                && regionEquals(data, start, prefix, 0, prefix.length);
    }

    private static boolean contains(byte[] data, byte[] value) {
        return contains(data, 0, data.length, value);
    }

    private static boolean contains(byte[] data, int start, int end, byte[] value) {
        if (value.length == 0) {
            return true;
        }
        for (int offset = start; offset + value.length <= end; offset++) {
            if (regionEquals(data, offset, value, 0, value.length)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionEquals(
            byte[] left, int leftOffset, byte[] right, int rightOffset, int length) {
        for (int index = 0; index < length; index++) {
            if (left[leftOffset + index] != right[rightOffset + index]) {
                return false;
            }
        }
        return true;
    }

    private static int u8(byte value) {
        return value & 0xff;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
