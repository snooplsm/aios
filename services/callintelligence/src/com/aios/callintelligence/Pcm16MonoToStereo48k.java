package com.aios.callintelligence;

/**
 * Streaming linear PCM converter for the Pixel telephony-TX mix port.
 *
 * Input is little-endian PCM16 mono at a supported speech-model rate. Output
 * is little-endian PCM16 stereo at 48 kHz. State is retained across arbitrary
 * read boundaries, including an odd byte split between reads.
 */
final class Pcm16MonoToStereo48k {
    static final int OUTPUT_SAMPLE_RATE_HZ = 48_000;
    static final int OUTPUT_BYTES_PER_FRAME = 4;

    private final int inputSampleRateHz;
    private long sourceFrameCount;
    private long nextOutputNumerator;
    private short previousSample;
    private boolean hasPreviousSample;
    private boolean hasPendingLowByte;
    private byte pendingLowByte;
    private boolean finished;

    Pcm16MonoToStereo48k(int inputSampleRateHz) {
        if (!isSupportedInputRate(inputSampleRateHz)) {
            throw new IllegalArgumentException("unsupported synthesis sample rate");
        }
        this.inputSampleRateHz = inputSampleRateHz;
    }

    int convert(byte[] input, int offset, int length, byte[] output) {
        if (finished) {
            throw new IllegalStateException("converter is finished");
        }
        if (input == null || output == null || offset < 0 || length < 0
                || offset > input.length - length) {
            throw new IllegalArgumentException("invalid PCM buffer range");
        }
        int inputIndex = offset;
        int end = offset + length;
        int outputOffset = 0;
        if (hasPendingLowByte && inputIndex < end) {
            short sample = littleEndian(pendingLowByte, input[inputIndex++]);
            hasPendingLowByte = false;
            outputOffset = accept(sample, output, outputOffset);
        }
        while (inputIndex + 1 < end) {
            short sample = littleEndian(input[inputIndex], input[inputIndex + 1]);
            inputIndex += 2;
            outputOffset = accept(sample, output, outputOffset);
        }
        if (inputIndex < end) {
            pendingLowByte = input[inputIndex];
            hasPendingLowByte = true;
        }
        return outputOffset;
    }

    int finish(byte[] output) {
        if (output == null) {
            throw new IllegalArgumentException("output is required");
        }
        if (finished) {
            return 0;
        }
        finished = true;
        hasPendingLowByte = false;
        if (!hasPreviousSample) {
            return 0;
        }
        int outputOffset = 0;
        long endExclusive = sourceFrameCount * OUTPUT_SAMPLE_RATE_HZ;
        while (nextOutputNumerator < endExclusive) {
            outputOffset = writeStereo(previousSample, output, outputOffset);
            nextOutputNumerator += inputSampleRateHz;
        }
        return outputOffset;
    }

    int maximumOutputBytes(int inputByteCount) {
        if (inputByteCount < 0) {
            throw new IllegalArgumentException("input byte count must be non-negative");
        }
        long possibleInputFrames = (inputByteCount + (hasPendingLowByte ? 1L : 0L) + 1L) / 2L;
        long frames = ((possibleInputFrames + 1L) * OUTPUT_SAMPLE_RATE_HZ
                + inputSampleRateHz - 1L) / inputSampleRateHz + 1L;
        long bytes = frames * OUTPUT_BYTES_PER_FRAME;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("input buffer is too large");
        }
        return (int) bytes;
    }

    private int accept(short currentSample, byte[] output, int outputOffset) {
        if (!hasPreviousSample) {
            previousSample = currentSample;
            hasPreviousSample = true;
            sourceFrameCount = 1L;
            return outputOffset;
        }
        long currentIndex = sourceFrameCount;
        long lowerNumerator = (currentIndex - 1L) * OUTPUT_SAMPLE_RATE_HZ;
        long upperNumerator = currentIndex * OUTPUT_SAMPLE_RATE_HZ;
        while (nextOutputNumerator <= upperNumerator) {
            long fraction = nextOutputNumerator - lowerNumerator;
            long weighted = (long) previousSample * (OUTPUT_SAMPLE_RATE_HZ - fraction)
                    + (long) currentSample * fraction;
            short interpolated = (short) (weighted / OUTPUT_SAMPLE_RATE_HZ);
            outputOffset = writeStereo(interpolated, output, outputOffset);
            nextOutputNumerator += inputSampleRateHz;
        }
        previousSample = currentSample;
        sourceFrameCount++;
        return outputOffset;
    }

    private static int writeStereo(short sample, byte[] output, int offset) {
        if (offset > output.length - OUTPUT_BYTES_PER_FRAME) {
            throw new IllegalArgumentException("output buffer is too small");
        }
        byte low = (byte) (sample & 0xff);
        byte high = (byte) ((sample >>> 8) & 0xff);
        output[offset] = low;
        output[offset + 1] = high;
        output[offset + 2] = low;
        output[offset + 3] = high;
        return offset + OUTPUT_BYTES_PER_FRAME;
    }

    private static short littleEndian(byte low, byte high) {
        return (short) (((high & 0xff) << 8) | (low & 0xff));
    }

    static boolean isSupportedInputRate(int sampleRateHz) {
        return sampleRateHz == 16_000 || sampleRateHz == 22_050
                || sampleRateHz == 24_000 || sampleRateHz == 44_100
                || sampleRateHz == 48_000;
    }
}
