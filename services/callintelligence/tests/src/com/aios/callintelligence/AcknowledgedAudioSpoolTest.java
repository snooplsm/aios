package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;

import org.junit.Test;

public final class AcknowledgedAudioSpoolTest {
    @Test
    public void finalTranscriptDeletesOnlyCompleteAcknowledgedSegments() throws Exception {
        File directory = Files.createTempDirectory("aios-audio-spool").toFile();
        try (AcknowledgedAudioSpool spool = new AcknowledgedAudioSpool(directory, 64)) {
            spool.write(new byte[160]);

            spool.acknowledgeThroughMillis(4L); // 128 bytes.

            assertEquals(160L, spool.capturedBytes());
            assertEquals(1, segmentCount(directory));
            assertEquals(32L, rawBytes(directory));
        } finally {
            CallArtifactRetention.deleteTree(directory);
        }
    }

    @Test
    public void acknowledgedTailRotatesThenDeletesActiveSegment() throws Exception {
        File directory = Files.createTempDirectory("aios-audio-spool").toFile();
        try (AcknowledgedAudioSpool spool = new AcknowledgedAudioSpool(directory, 64)) {
            spool.write(new byte[160]);

            spool.acknowledgeThroughMillis(5L); // 160 bytes.

            assertEquals(0, segmentCount(directory));
            spool.write(new byte[32]);
            assertEquals(192L, spool.capturedBytes());
        } finally {
            CallArtifactRetention.deleteTree(directory);
        }
    }

    @Test
    public void restartRecoversTimelineWithoutEvictingUnacknowledgedAudio()
            throws Exception {
        File directory = Files.createTempDirectory("aios-audio-spool").toFile();
        try {
            try (AcknowledgedAudioSpool first =
                         new AcknowledgedAudioSpool(directory, 64)) {
                first.write(new byte[96]);
            }
            try (AcknowledgedAudioSpool recovered =
                         new AcknowledgedAudioSpool(directory, 64)) {
                assertEquals(96L, recovered.capturedBytes());
                assertEquals(96L, rawBytes(directory));
                recovered.write(new byte[32]);
                assertEquals(128L, recovered.capturedBytes());
            }
        } finally {
            CallArtifactRetention.deleteTree(directory);
        }
    }

    @Test
    public void replayStartsAtDurableAcknowledgementWithoutDuplicatingPrefix()
            throws Exception {
        File directory = Files.createTempDirectory("aios-audio-spool").toFile();
        try {
            byte[] pcm = new byte[160];
            for (int index = 0; index < pcm.length; index++) pcm[index] = (byte) index;
            try (AcknowledgedAudioSpool spool =
                         new AcknowledgedAudioSpool(directory, 64)) {
                spool.write(pcm);
                spool.acknowledgeThroughMillis(3L); // 96 bytes.
            }
            try (AcknowledgedAudioSpool recovered =
                         new AcknowledgedAudioSpool(directory, 64)) {
                ByteArrayOutputStream replay = new ByteArrayOutputStream();
                assertEquals(96L, recovered.replayUnacknowledgedTo(replay));
                assertEquals(64, replay.size());
                for (int index = 0; index < replay.size(); index++) {
                    assertEquals(pcm[index + 96], replay.toByteArray()[index]);
                }
            }
        } finally {
            CallArtifactRetention.deleteTree(directory);
        }
    }

    private static int segmentCount(File directory) {
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".pcm"));
        return files == null ? 0 : files.length;
    }

    private static long rawBytes(File directory) {
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".pcm"));
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) total += file.length();
        return total;
    }
}
