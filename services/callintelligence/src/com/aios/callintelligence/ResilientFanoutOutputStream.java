package com.aios.callintelligence;

import java.io.IOException;
import java.io.OutputStream;

/** Required local sink plus a best-effort inference sink that may disappear. */
final class ResilientFanoutOutputStream extends OutputStream {
    private final OutputStream primary;
    private OutputStream secondary;
    private boolean closed;
    private long primaryBytesWritten;
    private long secondaryStartByteOffset = -1L;

    ResilientFanoutOutputStream(OutputStream primary, OutputStream secondary) {
        this(primary, secondary, 0L);
    }

    ResilientFanoutOutputStream(
            OutputStream primary, OutputStream secondary, long initialPrimaryBytesWritten) {
        if (primary == null) {
            throw new NullPointerException("primary sink is required");
        }
        if (initialPrimaryBytesWritten < 0L) {
            throw new IllegalArgumentException("initial PCM byte count cannot be negative");
        }
        this.primary = primary;
        this.secondary = secondary;
        primaryBytesWritten = initialPrimaryBytesWritten;
        if (secondary != null) secondaryStartByteOffset = initialPrimaryBytesWritten;
    }

    @Override
    public synchronized void write(int value) throws IOException {
        if (primaryBytesWritten == Long.MAX_VALUE) {
            throw new IOException("primary PCM byte counter exhausted");
        }
        primary.write(value);
        primaryBytesWritten++;
        writeSecondary(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
        if (length > Long.MAX_VALUE - primaryBytesWritten) {
            throw new IOException("primary PCM byte counter exhausted");
        }
        primary.write(buffer, offset, length);
        primaryBytesWritten += length;
        writeSecondary(buffer, offset, length);
    }

    @Override
    public synchronized void flush() throws IOException {
        primary.flush();
        if (secondary != null) {
            try {
                secondary.flush();
            } catch (IOException error) {
                dropSecondary();
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            primary.close();
        } catch (IOException error) {
            failure = error;
        }
        dropSecondary();
        if (failure != null) {
            throw failure;
        }
    }

    /** Atomically replaces the expendable inference sink while capture continues. */
    synchronized boolean replaceSecondary(OutputStream replacement) {
        return replaceSecondaryAtCurrentByteOffset(replacement) >= 0L;
    }

    synchronized long primaryBytesWritten() {
        return primaryBytesWritten;
    }

    synchronized long secondaryStartByteOffset() {
        return secondaryStartByteOffset;
    }

    /** Replays the unacknowledged primary spool, then atomically attaches live PCM. */
    synchronized long replaceSecondaryWithReplay(OutputStream replacement) {
        if (closed || replacement == null || !(primary instanceof AcknowledgedAudioSpool)) {
            closeQuietly(replacement);
            return -1L;
        }
        long replayStart;
        try {
            replayStart = ((AcknowledgedAudioSpool) primary)
                    .replayUnacknowledgedTo(replacement);
        } catch (IOException error) {
            closeQuietly(replacement);
            return -1L;
        }
        OutputStream previous = secondary;
        secondary = replacement;
        secondaryStartByteOffset = replayStart;
        if (previous != replacement) closeQuietly(previous);
        return replayStart;
    }

    /** Replaces the inference sink and returns its exact start in authoritative PCM bytes. */
    synchronized long replaceSecondaryAtCurrentByteOffset(OutputStream replacement) {
        if (closed) {
            closeQuietly(replacement);
            return -1L;
        }
        OutputStream previous = secondary;
        secondary = replacement;
        secondaryStartByteOffset = replacement == null ? -1L : primaryBytesWritten;
        if (previous != replacement) closeQuietly(previous);
        return primaryBytesWritten;
    }

    private void writeSecondary(byte[] buffer, int offset, int length) {
        if (secondary == null) {
            return;
        }
        try {
            secondary.write(buffer, offset, length);
        } catch (IOException error) {
            dropSecondary();
        }
    }

    private void dropSecondary() {
        OutputStream value = secondary;
        secondary = null;
        secondaryStartByteOffset = -1L;
        closeQuietly(value);
    }

    private static void closeQuietly(OutputStream value) {
        if (value == null) return;
        try {
            value.close();
        } catch (IOException ignored) {
            // The local capture remains authoritative.
        }
    }
}
