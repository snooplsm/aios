package com.aios.runtime.whispercpp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Pcm16EnergyVadTest {
    @Test
    public void silenceAndSubthresholdFramesAreRejected() {
        assertFalse(Pcm16EnergyVad.hasSpeech(frame(0, 1_600), 3_200, 0.005f));
        assertFalse(Pcm16EnergyVad.hasSpeech(frame(163, 1_600), 3_200, 0.005f));
    }

    @Test
    public void thresholdAndAlternatingSpeechFramesAreAccepted() {
        assertTrue(Pcm16EnergyVad.hasSpeech(frame(164, 1_600), 3_200, 0.005f));
        byte[] alternating = new byte[3_200];
        for (int sample = 0; sample < 1_600; sample++) {
            putSample(alternating, sample, sample % 2 == 0 ? 1_000 : -1_000);
        }
        assertTrue(Pcm16EnergyVad.hasSpeech(alternating, alternating.length, 0.005f));
    }

    @Test
    public void onlyTheDeclaredPrefixIsScanned() {
        byte[] pcm = frame(0, 4);
        putSample(pcm, 3, 20_000);

        assertFalse(Pcm16EnergyVad.hasSpeech(pcm, 6, 0.005f));
        assertTrue(Pcm16EnergyVad.hasSpeech(pcm, 8, 0.005f));
    }

    @Test
    public void malformedPcmAndThresholdsFailClosed() {
        byte[] pcm = new byte[4];
        assertThrows(NullPointerException.class,
                () -> Pcm16EnergyVad.hasSpeech(null, 0, 0.005f));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, -1, 0.005f));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, 3, 0.005f));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, 6, 0.005f));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, 4, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, 4, -0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16EnergyVad.hasSpeech(pcm, 4, 1.1f));
    }

    private static byte[] frame(int value, int sampleCount) {
        byte[] pcm = new byte[sampleCount * 2];
        for (int sample = 0; sample < sampleCount; sample++) {
            putSample(pcm, sample, value);
        }
        return pcm;
    }

    private static void putSample(byte[] pcm, int sample, int value) {
        pcm[sample * 2] = (byte) (value & 0xff);
        pcm[sample * 2 + 1] = (byte) ((value >>> 8) & 0xff);
    }
}
