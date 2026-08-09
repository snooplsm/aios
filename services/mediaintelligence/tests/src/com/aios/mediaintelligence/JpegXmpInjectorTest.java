package com.aios.mediaintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class JpegXmpInjectorTest {
    private static final String XMP =
            "<rdf:RDF><rdf:Description xmlns:aios='https://aios.dev/ns/media/1.0/'/>"
                    + "</rdf:RDF>";

    @Test
    public void insertionPreservesEverySourceByte() throws Exception {
        byte[] source = simpleJpeg(null, null);
        byte[] candidate = JpegXmpInjector.inject(source, XMP);
        assertTrue(JpegXmpInjector.containsOneAiosPacket(candidate));
        assertTrue(candidate.length > source.length);
        assertArrayEquals(
                Arrays.copyOfRange(source, source.length - 8, source.length),
                Arrays.copyOfRange(candidate, candidate.length - 8, candidate.length));
    }

    @Test
    public void mpfAndAppendedPayloadAreRejected() throws Exception {
        expectUnsafe(simpleJpeg(segment(0xe2, ascii("MPF\0data")), null));
        byte[] plain = simpleJpeg(null, null);
        expectUnsafe(concat(plain, ascii("fake-mp4")));
    }

    @Test
    public void existingXmpIsRejectedRatherThanOverwritten() throws Exception {
        expectUnsafe(simpleJpeg(
                segment(0xe1, concat(
                        ascii("http://ns.adobe.com/xap/1.0/\0"), ascii("<old/>"))),
                null));
    }

    private static void expectUnsafe(byte[] source) throws Exception {
        try {
            JpegXmpInjector.inject(source, XMP);
            fail("unsafe JPEG should be rejected");
        } catch (JpegXmpInjector.UnsafeJpegException expected) {
            // Expected.
        }
    }

    private static byte[] simpleJpeg(byte[] extraHeader, byte[] entropy) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        value.write(0xff);
        value.write(0xd8);
        write(value, segment(0xe0, concat(ascii("JFIF\0"), new byte[9])));
        write(value, segment(0xe1, ascii("Exif\0\0MM\0*")));
        if (extraHeader != null) {
            write(value, extraHeader);
        }
        write(value, segment(0xdb, new byte[]{0, 1, 2, 3}));
        write(value, segment(0xda, new byte[]{1, 1, 0, 0, 0x3f, 0}));
        write(value, entropy == null
                ? new byte[]{0x11, (byte) 0xff, 0, 0x22, (byte) 0xff, (byte) 0xd0, 0x33}
                : entropy);
        value.write(0xff);
        value.write(0xd9);
        return value.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        int length = payload.length + 2;
        value.write(0xff);
        value.write(marker);
        value.write(length >>> 8);
        value.write(length);
        write(value, payload);
        return value.toByteArray();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void write(ByteArrayOutputStream stream, byte[] value) {
        stream.write(value, 0, value.length);
    }
}
