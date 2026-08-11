package com.aios.callintelligence;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

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
    public void replacementAfterCloseIsRejectedAndClosed() throws Exception {
        ResilientFanoutOutputStream fanout = new ResilientFanoutOutputStream(
                new ByteArrayOutputStream(), null);
        fanout.close();
        TrackingStream replacement = new TrackingStream();

        assertFalse(fanout.replaceSecondary(replacement));
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
