package com.aios.callintelligence;

import java.io.IOException;
import java.io.OutputStream;

/** Required local sink plus a best-effort inference sink that may disappear. */
final class ResilientFanoutOutputStream extends OutputStream {
    private final OutputStream primary;
    private OutputStream secondary;
    private boolean closed;

    ResilientFanoutOutputStream(OutputStream primary, OutputStream secondary) {
        if (primary == null) {
            throw new NullPointerException("primary sink is required");
        }
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public synchronized void write(int value) throws IOException {
        primary.write(value);
        writeSecondary(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
        primary.write(buffer, offset, length);
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
        if (closed) {
            closeQuietly(replacement);
            return false;
        }
        OutputStream previous = secondary;
        secondary = replacement;
        if (previous != replacement) closeQuietly(previous);
        return true;
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
