package com.aios.runtime.whispercpp;

import java.util.Objects;

/** Allocation-free energy gate for little-endian mono PCM16 frames. */
final class Pcm16EnergyVad {
    private static final double PCM16_SCALE = 32_768.0;

    private Pcm16EnergyVad() {}

    static boolean hasSpeech(byte[] pcm, int byteCount, float minimumRms) {
        Objects.requireNonNull(pcm, "pcm");
        if (byteCount < 0 || byteCount > pcm.length || (byteCount & 1) != 0) {
            throw new IllegalArgumentException("PCM16 byte count must be even and in bounds");
        }
        if (!Float.isFinite(minimumRms) || minimumRms < 0.0f || minimumRms > 1.0f) {
            throw new IllegalArgumentException("minimum RMS must be finite and in [0, 1]");
        }
        int sampleCount = byteCount / 2;
        if (sampleCount == 0) return false;

        long sumSquares = 0L;
        for (int offset = 0; offset < byteCount; offset += 2) {
            int low = pcm[offset] & 0xff;
            int high = pcm[offset + 1];
            int sample = (short) ((high << 8) | low);
            sumSquares += (long) sample * sample;
        }
        double threshold = minimumRms * PCM16_SCALE;
        return sumSquares >= threshold * threshold * sampleCount;
    }
}
