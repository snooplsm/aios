package com.aios.mediaintelligence;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Byte-preserving XMP insertion for a deliberately narrow, still-image PNG subset. */
final class PngXmpInjector {
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final byte[] XMP_KEYWORD =
            "XML:com.adobe.xmp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AIOS_NAMESPACE =
            "https://aios.dev/ns/media/1.0/".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_XMP_BYTES = 1024 * 1024;

    static final class UnsafePngException extends Exception {
        UnsafePngException(String message) {
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

    private PngXmpInjector() {}

    static byte[] inject(byte[] original, String xmpPacket) throws UnsafePngException {
        Inspection source = inspect(original, false);
        byte[] packet = xmpPacket.getBytes(StandardCharsets.UTF_8);
        if (packet.length == 0 || packet.length > MAX_XMP_BYTES) {
            throw new UnsafePngException("XMP packet exceeds the PNG writer bound");
        }
        if (!contains(packet, AIOS_NAMESPACE)) {
            throw new UnsafePngException("XMP packet does not use the AIOS namespace");
        }
        if (contains(packet, new byte[]{0})) {
            throw new UnsafePngException("XMP packet contains a NUL byte");
        }

        byte[] payload = new byte[XMP_KEYWORD.length + 5 + packet.length];
        int position = 0;
        System.arraycopy(XMP_KEYWORD, 0, payload, position, XMP_KEYWORD.length);
        position += XMP_KEYWORD.length;
        // keyword terminator, compression flag/method, language terminator, translated-keyword
        // terminator are all zero and were supplied by the new byte array.
        position += 5;
        System.arraycopy(packet, 0, payload, position, packet.length);
        byte[] chunk = chunk("iTXt", payload);

        if ((long) original.length + chunk.length > Integer.MAX_VALUE) {
            throw new UnsafePngException("PNG candidate exceeds the in-memory writer bound");
        }
        byte[] candidate = new byte[original.length + chunk.length];
        System.arraycopy(original, 0, candidate, 0, source.insertionOffset);
        System.arraycopy(chunk, 0, candidate, source.insertionOffset, chunk.length);
        System.arraycopy(
                original,
                source.insertionOffset,
                candidate,
                source.insertionOffset + chunk.length,
                original.length - source.insertionOffset);

        inspect(candidate, true);
        if (!regionEquals(candidate, 0, original, 0, source.insertionOffset)
                || !regionEquals(
                candidate,
                source.insertionOffset + chunk.length,
                original,
                source.insertionOffset,
                original.length - source.insertionOffset)) {
            throw new UnsafePngException("lossless PNG byte-preservation check failed");
        }
        return candidate;
    }

    static boolean containsOneAiosPacket(byte[] candidate) {
        try {
            return inspect(candidate, true).aiosXmpCount == 1;
        } catch (UnsafePngException error) {
            return false;
        }
    }

    private static Inspection inspect(byte[] data, boolean allowAiosXmp)
            throws UnsafePngException {
        if (data.length < SIGNATURE.length
                || !regionEquals(data, 0, SIGNATURE, 0, SIGNATURE.length)) {
            throw new UnsafePngException("missing PNG signature");
        }

        int position = SIGNATURE.length;
        int chunkIndex = 0;
        int firstIdatOffset = -1;
        int aiosXmpCount = 0;
        int bitDepth = -1;
        int colorType = -1;
        int idatBytes = 0;
        boolean sawIhdr = false;
        boolean sawPlte = false;
        boolean sawIdat = false;
        boolean idatClosed = false;
        boolean sawIend = false;

        while (position < data.length) {
            int chunkStart = position;
            if (data.length - position < 12) {
                throw new UnsafePngException("truncated PNG chunk");
            }
            long unsignedLength = u32(data, position);
            if (unsignedLength > Integer.MAX_VALUE) {
                throw new UnsafePngException("PNG chunk exceeds the in-memory writer bound");
            }
            int length = (int) unsignedLength;
            long endLong = (long) position + 12L + length;
            if (endLong > data.length) {
                throw new UnsafePngException("truncated PNG chunk data");
            }
            int typeOffset = position + 4;
            int dataOffset = typeOffset + 4;
            int crcOffset = dataOffset + length;
            int end = crcOffset + 4;
            validateType(data, typeOffset);
            if (u32(data, crcOffset) != crc32(data, typeOffset, 4 + length)) {
                throw new UnsafePngException("PNG chunk CRC mismatch");
            }
            String type = new String(data, typeOffset, 4, StandardCharsets.US_ASCII);

            if (chunkIndex == 0 && !"IHDR".equals(type)) {
                throw new UnsafePngException("IHDR must be the first PNG chunk");
            }
            if ("IHDR".equals(type)) {
                if (sawIhdr || chunkIndex != 0 || length != 13) {
                    throw new UnsafePngException("invalid or duplicate PNG IHDR");
                }
                bitDepth = u8(data[dataOffset + 8]);
                colorType = u8(data[dataOffset + 9]);
                validateIhdr(data, dataOffset, bitDepth, colorType);
                sawIhdr = true;
            } else if (!sawIhdr) {
                throw new UnsafePngException("missing PNG IHDR");
            } else if ("PLTE".equals(type)) {
                if (sawPlte || sawIdat || length == 0 || length > 768 || length % 3 != 0
                        || colorType == 0 || colorType == 4
                        || (colorType == 3 && length / 3 > 1 << bitDepth)) {
                    throw new UnsafePngException("invalid PNG PLTE");
                }
                sawPlte = true;
            } else if ("IDAT".equals(type)) {
                if (idatClosed || (colorType == 3 && !sawPlte)) {
                    throw new UnsafePngException("invalid PNG IDAT ordering");
                }
                if (!sawIdat) {
                    firstIdatOffset = chunkStart;
                }
                sawIdat = true;
                if (Integer.MAX_VALUE - idatBytes < length) {
                    throw new UnsafePngException("PNG IDAT payload is too large");
                }
                idatBytes += length;
            } else if ("IEND".equals(type)) {
                if (sawIend || length != 0 || !sawIdat || idatBytes == 0
                        || end != data.length) {
                    throw new UnsafePngException("invalid PNG IEND");
                }
                sawIend = true;
            } else {
                if (isCritical(data[typeOffset])) {
                    throw new UnsafePngException("unknown critical PNG chunk: " + type);
                }
                if ("acTL".equals(type) || "fcTL".equals(type) || "fdAT".equals(type)) {
                    throw new UnsafePngException("animated PNG is not writable");
                }
                if ("dSIG".equals(type)) {
                    throw new UnsafePngException("digitally signed PNG is not writable");
                }
                aiosXmpCount += inspectTextChunk(
                        type, data, dataOffset, crcOffset, allowAiosXmp);
            }

            if (sawIdat && !"IDAT".equals(type) && !"IEND".equals(type)) {
                idatClosed = true;
            }
            position = end;
            chunkIndex++;
        }

        if (!sawIhdr || !sawIdat || !sawIend || firstIdatOffset < 0) {
            throw new UnsafePngException("incomplete PNG container");
        }
        if (colorType == 3 && !sawPlte) {
            throw new UnsafePngException("indexed PNG is missing PLTE");
        }
        if (allowAiosXmp && aiosXmpCount != 1) {
            throw new UnsafePngException("candidate must contain exactly one AIOS XMP packet");
        }
        if (!allowAiosXmp && aiosXmpCount != 0) {
            throw new UnsafePngException("source already contains AIOS XMP");
        }
        return new Inspection(firstIdatOffset, aiosXmpCount);
    }

    private static int inspectTextChunk(
            String type, byte[] data, int start, int end, boolean allowAiosXmp)
            throws UnsafePngException {
        if (!"iTXt".equals(type) && !"tEXt".equals(type) && !"zTXt".equals(type)) {
            return 0;
        }
        int terminator = indexOf(data, start, end, (byte) 0);
        if (terminator < 0 || !regionEquals(
                data, start, XMP_KEYWORD, 0, XMP_KEYWORD.length)
                || terminator - start != XMP_KEYWORD.length) {
            return 0;
        }
        if (!"iTXt".equals(type)) {
            throw new UnsafePngException("existing nonstandard PNG XMP text chunk");
        }
        int control = terminator + 1;
        if (control + 4 > end
                || data[control] != 0
                || data[control + 1] != 0
                || data[control + 2] != 0
                || data[control + 3] != 0) {
            throw new UnsafePngException("compressed or localized PNG XMP is not writable");
        }
        int packetStart = control + 4;
        if (allowAiosXmp && packetStart < end
                && contains(data, packetStart, end, AIOS_NAMESPACE)) {
            return 1;
        }
        throw new UnsafePngException("existing non-AIOS or duplicate PNG XMP packet");
    }

    private static void validateIhdr(byte[] data, int start, int bitDepth, int colorType)
            throws UnsafePngException {
        long width = u32(data, start);
        long height = u32(data, start + 4);
        if (width == 0 || width > Integer.MAX_VALUE
                || height == 0 || height > Integer.MAX_VALUE) {
            throw new UnsafePngException("invalid PNG dimensions");
        }
        boolean validDepth = switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4
                    || bitDepth == 8 || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
        if (!validDepth
                || u8(data[start + 10]) != 0
                || u8(data[start + 11]) != 0
                || u8(data[start + 12]) > 1) {
            throw new UnsafePngException("unsupported PNG IHDR fields");
        }
    }

    private static void validateType(byte[] data, int start) throws UnsafePngException {
        for (int index = 0; index < 4; index++) {
            int value = u8(data[start + index]);
            if (!(value >= 'A' && value <= 'Z') && !(value >= 'a' && value <= 'z')) {
                throw new UnsafePngException("invalid PNG chunk type");
            }
        }
        if ((data[start + 2] & 0x20) != 0) {
            throw new UnsafePngException("invalid PNG reserved chunk-type bit");
        }
    }

    private static boolean isCritical(byte firstTypeByte) {
        return (firstTypeByte & 0x20) == 0;
    }

    private static byte[] chunk(String type, byte[] payload) {
        byte[] result = new byte[payload.length + 12];
        putU32(result, 0, payload.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(typeBytes, 0, result, 4, 4);
        System.arraycopy(payload, 0, result, 8, payload.length);
        putU32(result, 8 + payload.length, crc32(result, 4, 4 + payload.length));
        return result;
    }

    private static long crc32(byte[] data, int start, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, start, length);
        return crc.getValue();
    }

    private static long u32(byte[] data, int start) {
        return (long) u8(data[start]) << 24
                | (long) u8(data[start + 1]) << 16
                | (long) u8(data[start + 2]) << 8
                | u8(data[start + 3]);
    }

    private static void putU32(byte[] data, int start, long value) {
        data[start] = (byte) (value >>> 24);
        data[start + 1] = (byte) (value >>> 16);
        data[start + 2] = (byte) (value >>> 8);
        data[start + 3] = (byte) value;
    }

    private static int indexOf(byte[] data, int start, int end, byte value) {
        for (int index = start; index < end; index++) {
            if (data[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private static boolean contains(byte[] data, byte[] value) {
        return contains(data, 0, data.length, value);
    }

    private static boolean contains(byte[] data, int start, int end, byte[] value) {
        if (value.length == 0) {
            return true;
        }
        if (start < 0 || end < start || end > data.length || value.length > end - start) {
            return false;
        }
        for (int offset = start; offset <= end - value.length; offset++) {
            if (regionEquals(data, offset, value, 0, value.length)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionEquals(
            byte[] left, int leftOffset, byte[] right, int rightOffset, int length) {
        if (leftOffset < 0 || rightOffset < 0 || length < 0
                || leftOffset > left.length || rightOffset > right.length
                || length > left.length - leftOffset || length > right.length - rightOffset) {
            return false;
        }
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
}
