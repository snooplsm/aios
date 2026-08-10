package com.aios.mediaintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class PngXmpInjectorTest {
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final String XMP =
            "<rdf:RDF><rdf:Description xmlns:aios='https://aios.dev/ns/media/1.0/'/>"
                    + "</rdf:RDF>";

    @Test
    public void insertionPreservesEverySourceChunkAndIdatByte() throws Exception {
        byte[] colorProfile = chunk("cICP", new byte[]{1, 13, 0, 1});
        byte[] source = simplePng(colorProfile, null);
        int insertionOffset = findChunk(source, "IDAT");

        byte[] candidate = PngXmpInjector.inject(source, XMP);
        int candidateIdatOffset = findChunk(candidate, "IDAT");

        assertTrue(PngXmpInjector.containsOneAiosPacket(candidate));
        assertTrue(candidateIdatOffset > insertionOffset);
        assertArrayEquals(
                Arrays.copyOfRange(source, 0, insertionOffset),
                Arrays.copyOfRange(candidate, 0, insertionOffset));
        assertArrayEquals(
                Arrays.copyOfRange(source, insertionOffset, source.length),
                Arrays.copyOfRange(candidate, candidateIdatOffset, candidate.length));
    }

    @Test
    public void apngSignedAndUnknownCriticalChunksAreRejected() throws Exception {
        expectUnsafe(simplePng(chunk("acTL", new byte[8]), null));
        expectUnsafe(simplePng(chunk("dSIG", new byte[]{1}), null));
        expectUnsafe(simplePng(chunk("ABCD", new byte[]{1}), null));
    }

    @Test
    public void badCrcTrailerAndNonconsecutiveIdatAreRejected() throws Exception {
        byte[] badCrc = simplePng(null, null);
        badCrc[badCrc.length - 5] ^= 1;
        expectUnsafe(badCrc);
        expectUnsafe(concat(simplePng(null, null), new byte[]{1}));

        byte[] splitIdat = png(
                ihdr(),
                chunk("IDAT", new byte[]{1}),
                chunk("tEXt", ascii("note\0value")),
                chunk("IDAT", new byte[]{2}),
                chunk("IEND", new byte[0]));
        expectUnsafe(splitIdat);
    }

    @Test
    public void existingOrCompressedXmpIsRejected() throws Exception {
        byte[] packet = concat(
                ascii("XML:com.adobe.xmp\0\0\0\0\0"),
                XMP.getBytes(StandardCharsets.UTF_8));
        expectUnsafe(simplePng(chunk("iTXt", packet), null));

        byte[] compressed = concat(
                ascii("XML:com.adobe.xmp\0"), new byte[]{1, 0, 0, 0, 1});
        expectUnsafe(simplePng(chunk("iTXt", compressed), null));
    }

    @Test
    public void malformedCandidatesNeverReportAiosPacket() throws Exception {
        byte[] candidate = PngXmpInjector.inject(simplePng(null, null), XMP);
        assertTrue(PngXmpInjector.containsOneAiosPacket(candidate));
        candidate[candidate.length - 1] ^= 1;
        assertFalse(PngXmpInjector.containsOneAiosPacket(candidate));
    }

    private static void expectUnsafe(byte[] source) throws Exception {
        try {
            PngXmpInjector.inject(source, XMP);
            fail("unsafe PNG should be rejected");
        } catch (PngXmpInjector.UnsafePngException expected) {
            // Expected.
        }
    }

    private static byte[] simplePng(byte[] extraHeader, byte[] idat) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, SIGNATURE);
        write(output, ihdr());
        if (extraHeader != null) {
            write(output, extraHeader);
        }
        write(output, chunk("IDAT", idat == null
                ? new byte[]{0x78, 1, 1, 5, 0, (byte) 0xfa, (byte) 0xff,
                        0, (byte) 0xff, 0, 0, (byte) 0xff, 5, 0, 1, (byte) 0xff}
                : idat));
        write(output, chunk("IEND", new byte[0]));
        return output.toByteArray();
    }

    private static byte[] ihdr() {
        return chunk("IHDR", new byte[]{
                0, 0, 0, 1,
                0, 0, 0, 1,
                8, 6, 0, 0, 0
        });
    }

    private static byte[] png(byte[]... chunks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, SIGNATURE);
        for (byte[] item : chunks) {
            write(output, item);
        }
        return output.toByteArray();
    }

    private static byte[] chunk(String type, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU32(output, payload.length);
        byte[] typeBytes = ascii(type);
        write(output, typeBytes);
        write(output, payload);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(payload);
        writeU32(output, crc.getValue());
        return output.toByteArray();
    }

    private static int findChunk(byte[] png, String expectedType) {
        int position = SIGNATURE.length;
        while (position + 12 <= png.length) {
            int length = (png[position] & 0xff) << 24
                    | (png[position + 1] & 0xff) << 16
                    | (png[position + 2] & 0xff) << 8
                    | png[position + 3] & 0xff;
            String type = new String(png, position + 4, 4, StandardCharsets.US_ASCII);
            if (expectedType.equals(type)) {
                return position;
            }
            position += 12 + length;
        }
        throw new AssertionError("missing chunk " + expectedType);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeU32(ByteArrayOutputStream output, long value) {
        output.write((int) (value >>> 24));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 8));
        output.write((int) value);
    }

    private static void write(ByteArrayOutputStream output, byte[] value) {
        output.write(value, 0, value.length);
    }
}
