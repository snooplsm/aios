package com.aios.callintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.io.File;

import org.junit.Test;

public final class ResilientFanoutOutputStreamTest {
    @Test
    public void replacementReceivesFutureAudioWithoutInterruptingPrimary() throws Exception {
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        TrackingStream first = new TrackingStream();
        TrackingStream second = new TrackingStream();
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(primary, first);

        fanout.write(new byte[]{1, 2});
        assertTrue(fanout.replaceSecondary(second));
        fanout.write(new byte[]{3, 4});

        assertTrue(first.closed);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, primary.toByteArray());
        assertArrayEquals(new byte[]{1, 2}, first.toByteArray());
        assertArrayEquals(new byte[]{3, 4}, second.toByteArray());
    }

    @Test
    public void failedSecondaryCanBeRestored() throws Exception {
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(
                primary, new FailingStream());
        fanout.write(1);
        TrackingStream replacement = new TrackingStream();

        assertTrue(fanout.replaceSecondary(replacement));
        fanout.write(2);

        assertArrayEquals(new byte[]{1, 2}, primary.toByteArray());
        assertArrayEquals(new byte[]{2}, replacement.toByteArray());
    }

    @Test
    public void replacementReportsAtomicAuthoritativePcmOffset() throws Exception {
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        TrackingStream replacement = new TrackingStream();
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(primary, null);
        fanout.write(new byte[3_200]);

        long offset = fanout.replaceSecondaryAtCurrentByteOffset(replacement);
        fanout.write(new byte[]{1, 2});

        assertEquals(3_200L, offset);
        assertEquals(3_202L, primary.size());
        assertArrayEquals(new byte[]{1, 2}, replacement.toByteArray());
    }

    @Test
    public void recoveredPrimaryOffsetIsPreservedAcrossInferenceRestart() throws Exception {
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(
                new ByteArrayOutputStream(), null, 9_600L);

        fanout.write(new byte[3_200]);

        assertEquals(12_800L, fanout.primaryBytesWritten());
        assertEquals(12_800L,
                fanout.replaceSecondaryAtCurrentByteOffset(new TrackingStream()));
    }

    @Test
    public void replacementReplaysOnlyUnacknowledgedAudioBeforeLivePcm() throws Exception {
        File directory = Files.createTempDirectory("aios-fanout-replay").toFile();
        try (AcknowledgedAudioSpool spool = new AcknowledgedAudioSpool(directory, 64)) {
            spool.write(new byte[96]);
            spool.acknowledgeThroughMillis(2L); // 64 bytes.
            ResilientFanoutOutputStream fanout =
                    new ResilientFanoutOutputStream(spool, null, 96L);
            TrackingStream replacement = new TrackingStream();

            assertEquals(64L, fanout.replaceSecondaryWithReplay(replacement));
            fanout.write(new byte[32]);

            assertEquals(64, replacement.size());
            assertEquals(64L, fanout.secondaryStartByteOffset());
        } finally {
            CallArtifactRetention.deleteTree(directory);
        }
    }

    @Test
    public void replacementAfterCloseIsRejectedAndClosed() throws Exception {
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(
                new ByteArrayOutputStream(), null);
        fanout.close();
        TrackingStream replacement = new TrackingStream();

        assertFalse(fanout.replaceSecondary(replacement));
        assertEquals(-1L, fanout.replaceSecondaryAtCurrentByteOffset(new TrackingStream()));
        assertTrue(replacement.closed);
    }

    private static final class TrackingStream extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FailingStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("inference sink failed");
        }
    }
}
