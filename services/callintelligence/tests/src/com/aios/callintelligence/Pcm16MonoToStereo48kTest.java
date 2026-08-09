package com.aios.callintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public final class Pcm16MonoToStereo48kTest {
    @Test
    public void doubles24kMonoAndDuplicatesStereoChannels() {
        Pcm16MonoToStereo48k converter = new Pcm16MonoToStereo48k(24_000);
        byte[] output = new byte[converter.maximumOutputBytes(4)];
        int converted = converter.convert(pcm(0, 1_000), 0, 4, output);
        byte[] tail = new byte[16];
        int finished = converter.finish(tail);

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(output, 0, converted);
        combined.write(tail, 0, finished);
        assertArrayEquals(stereoPcm(0, 500, 1_000, 1_000), combined.toByteArray());
    }

    @Test
    public void oddReadBoundariesDoNotChangeOutput() {
        byte[] input = pcm(-2_000, -1_000, 0, 1_000, 2_000);
        assertArrayEquals(convertInChunks(input, input.length), convertInChunks(input, 1));
    }

    @Test
    public void preservesOneSecondDurationAt22050Hz() {
        Pcm16MonoToStereo48k converter = new Pcm16MonoToStereo48k(22_050);
        byte[] input = new byte[22_050 * 2];
        byte[] output = new byte[converter.maximumOutputBytes(input.length)];
        int converted = converter.convert(input, 0, input.length, output);
        int finished = converter.finish(new byte[32]);
        assertEquals(48_000 * 4, converted + finished);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedSampleRates() {
        new Pcm16MonoToStereo48k(44_100);
    }

    private static byte[] convertInChunks(byte[] input, int chunkSize) {
        Pcm16MonoToStereo48k converter = new Pcm16MonoToStereo48k(24_000);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < input.length) {
            int length = Math.min(chunkSize, input.length - offset);
            byte[] output = new byte[converter.maximumOutputBytes(length)];
            int count = converter.convert(input, offset, length, output);
            result.write(output, 0, count);
            offset += length;
        }
        byte[] tail = new byte[16];
        int count = converter.finish(tail);
        result.write(tail, 0, count);
        return result.toByteArray();
    }

    private static byte[] pcm(int... samples) {
        byte[] result = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            short sample = (short) samples[index];
            result[index * 2] = (byte) (sample & 0xff);
            result[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return result;
    }

    private static byte[] stereoPcm(int... frames) {
        byte[] result = new byte[frames.length * 4];
        for (int index = 0; index < frames.length; index++) {
            short sample = (short) frames[index];
            byte low = (byte) (sample & 0xff);
            byte high = (byte) ((sample >>> 8) & 0xff);
            int offset = index * 4;
            result[offset] = low;
            result[offset + 1] = high;
            result[offset + 2] = low;
            result[offset + 3] = high;
        }
        return result;
    }
}
