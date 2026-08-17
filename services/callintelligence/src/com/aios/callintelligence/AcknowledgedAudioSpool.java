package com.aios.callintelligence;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Loss-resistant PCM spool whose processed prefix is deleted only after a
 * finalized transcript has been durably committed by the caller.
 */
final class AcknowledgedAudioSpool extends OutputStream {
    static final long PCM_BYTES_PER_MILLISECOND = 32L; // 16 kHz mono PCM16.
    static final int DEFAULT_SEGMENT_BYTES = 160_000; // Five seconds.

    private final File directory;
    private final int segmentBytes;
    private FileOutputStream current;
    private File currentFile;
    private long currentStart;
    private long currentLength;
    private long capturedBytes;
    private long acknowledgedBytes;
    private boolean closed;

    AcknowledgedAudioSpool(File directory) throws IOException {
        this(directory, DEFAULT_SEGMENT_BYTES);
    }

    AcknowledgedAudioSpool(File directory, int segmentBytes) throws IOException {
        if (directory == null) throw new NullPointerException("spool directory is required");
        if (segmentBytes <= 0) throw new IllegalArgumentException("segment size must be positive");
        this.directory = directory;
        this.segmentBytes = segmentBytes;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create raw-audio spool");
        }
        recoverCapturedBytes();
        recoverAcknowledgedBytes();
    }

    synchronized long capturedBytes() {
        return capturedBytes;
    }

    synchronized long unacknowledgedStartBytes() {
        return acknowledgedBytes;
    }

    @Override
    public synchronized void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length)
            throws IOException {
        if (closed) throw new IOException("raw-audio spool is closed");
        if (buffer == null) throw new NullPointerException("PCM buffer is required");
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException("invalid PCM buffer range");
        }
        while (length > 0) {
            openCurrent();
            int chunk = (int) Math.min(length, segmentBytes - currentLength);
            current.write(buffer, offset, chunk);
            offset += chunk;
            length -= chunk;
            currentLength += chunk;
            capturedBytes = Math.addExact(capturedBytes, chunk);
            if (currentLength == segmentBytes) closeCurrent();
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (current != null) current.flush();
    }

    /**
     * Removes every complete segment ending at or before the final transcript
     * boundary. A partial or provisional transcript must never call this.
     */
    synchronized void acknowledgeThroughMillis(long finalEndMillis) throws IOException {
        if (finalEndMillis < 0L) throw new IllegalArgumentException("negative transcript end");
        long acknowledgedBytes;
        try {
            acknowledgedBytes = Math.multiplyExact(
                    finalEndMillis, PCM_BYTES_PER_MILLISECOND);
        } catch (ArithmeticException overflow) {
            throw new IOException("transcript boundary overflows PCM timeline", overflow);
        }
        acknowledgedBytes = Math.min(acknowledgedBytes, capturedBytes);
        if (acknowledgedBytes > this.acknowledgedBytes) {
            persistAcknowledgedBytes(acknowledgedBytes);
            this.acknowledgedBytes = acknowledgedBytes;
        }
        if (current != null && currentStart + currentLength <= acknowledgedBytes) {
            closeCurrent();
        }
        IOException failure = null;
        for (File segment : segments()) {
            long start = parseStart(segment);
            long end;
            try {
                end = Math.addExact(start, segment.length());
            } catch (ArithmeticException overflow) {
                failure = new IOException("raw-audio segment boundary overflow", overflow);
                continue;
            }
            if (segment.equals(currentFile) || end > this.acknowledgedBytes) continue;
            if (segment.exists() && !segment.delete()) {
                failure = new IOException("cannot delete acknowledged raw-audio segment");
            }
        }
        if (failure != null) throw failure;
    }

    /** Replays only PCM that has no durably committed final transcript. */
    synchronized long replayUnacknowledgedTo(OutputStream destination) throws IOException {
        if (closed) throw new IOException("raw-audio spool is closed");
        if (destination == null) throw new NullPointerException("replay destination is required");
        if (current != null) current.flush();
        byte[] buffer = new byte[64 * 1024];
        for (File segment : segments()) {
            long start = parseStart(segment);
            long end = Math.addExact(start, segment.length());
            if (end <= acknowledgedBytes) continue;
            try (FileInputStream input = new FileInputStream(segment)) {
                long skip = Math.max(0L, acknowledgedBytes - start);
                while (skip > 0L) {
                    long skipped = input.skip(skip);
                    if (skipped <= 0L) throw new IOException("cannot seek raw-audio replay");
                    skip -= skipped;
                }
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) destination.write(buffer, 0, read);
                }
            }
        }
        destination.flush();
        return acknowledgedBytes;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        closeCurrent();
    }

    private void recoverCapturedBytes() throws IOException {
        long maximum = 0L;
        for (File segment : segments()) {
            long start = parseStart(segment);
            long end;
            try {
                end = Math.addExact(start, segment.length());
            } catch (ArithmeticException overflow) {
                throw new IOException("raw-audio segment boundary overflow", overflow);
            }
            maximum = Math.max(maximum, end);
        }
        capturedBytes = maximum;
    }

    private void recoverAcknowledgedBytes() throws IOException {
        File marker = new File(directory, "acknowledged.offset");
        if (!marker.exists()) return;
        if (marker.length() <= 0L || marker.length() > 32L) {
            throw new IOException("malformed raw-audio acknowledgement size");
        }
        byte[] encoded = new byte[(int) marker.length()];
        try (FileInputStream input = new FileInputStream(marker)) {
            int offset = 0;
            while (offset < encoded.length) {
                int read = input.read(encoded, offset, encoded.length - offset);
                if (read < 0) throw new IOException("truncated raw-audio acknowledgement");
                offset += read;
            }
        }
        try {
            acknowledgedBytes = Long.parseLong(
                    new String(encoded, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException malformed) {
            throw new IOException("malformed raw-audio acknowledgement", malformed);
        }
        if (acknowledgedBytes < 0L || acknowledgedBytes > capturedBytes) {
            throw new IOException("raw-audio acknowledgement is outside the capture timeline");
        }
    }

    private void persistAcknowledgedBytes(long value) throws IOException {
        File temporary = new File(directory, "acknowledged.offset.tmp");
        File marker = new File(directory, "acknowledged.offset");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
            stream.write('\n');
            stream.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(), marker.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temporary.toPath(), marker.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void openCurrent() throws IOException {
        if (current != null) return;
        currentStart = capturedBytes;
        currentLength = 0L;
        currentFile = new File(directory, String.format("%020d.pcm", currentStart));
        if (currentFile.exists()) {
            throw new IOException("raw-audio spool offset collision");
        }
        current = new FileOutputStream(currentFile);
    }

    private void closeCurrent() throws IOException {
        if (current == null) return;
        FileOutputStream stream = current;
        current = null;
        currentFile = null;
        currentLength = 0L;
        stream.flush();
        stream.getFD().sync();
        stream.close();
    }

    private File[] segments() throws IOException {
        File[] files = directory.listFiles((ignored, name) -> name.matches("[0-9]{20}\\.pcm"));
        if (files == null) throw new IOException("cannot enumerate raw-audio spool");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static long parseStart(File segment) throws IOException {
        String name = segment.getName();
        try {
            return Long.parseLong(name.substring(0, name.length() - 4));
        } catch (IndexOutOfBoundsException | NumberFormatException malformed) {
            throw new IOException("malformed raw-audio segment", malformed);
        }
    }
}
