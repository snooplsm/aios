package com.aios.mediaintelligence;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Creates and verifies a no-reencode MP4 copy with embedded AIOS metadata tracks. */
final class VideoEnhancedCopyMuxer {
    interface ProgressListener {
        void onProgress(int percent);
    }

    static final class Result {
        final int sourceTrackCount;
        final int copiedSampleCount;
        final long copiedSampleBytes;
        final boolean hasSubtitleTrack;

        Result(
                int sourceTrackCount,
                int copiedSampleCount,
                long copiedSampleBytes,
                boolean hasSubtitleTrack) {
            this.sourceTrackCount = sourceTrackCount;
            this.copiedSampleCount = copiedSampleCount;
            this.copiedSampleBytes = copiedSampleBytes;
            this.hasSubtitleTrack = hasSubtitleTrack;
        }
    }

    private static final int MAX_SOURCE_TRACKS = 32;
    private static final long MAX_SAMPLE_BYTES = 128L * 1024L * 1024L;
    private static final int INITIAL_SAMPLE_BUFFER_BYTES = 1024 * 1024;
    private static final String[] INTEGER_FORMAT_KEYS = {
            MediaFormat.KEY_WIDTH,
            MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_CHANNEL_COUNT,
            MediaFormat.KEY_SAMPLE_RATE,
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_TRANSFER,
            MediaFormat.KEY_COLOR_RANGE,
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_ROTATION,
    };

    private VideoEnhancedCopyMuxer() {}

    static Result create(
            ParcelFileDescriptor sourceDescriptor,
            ParcelFileDescriptor outputDescriptor,
            VideoEmbeddedMetadata.Data metadata,
            ProgressListener progress) throws IOException {
        if (sourceDescriptor == null || outputDescriptor == null || metadata == null) {
            throw new IllegalArgumentException("enhanced-video descriptors are required");
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean muxerStopped = false;
        try {
            extractor.setDataSource(sourceDescriptor.getFileDescriptor());
            retriever.setDataSource(sourceDescriptor.getFileDescriptor());
            SourcePlan plan = inspectSource(extractor, containerDurationUs(retriever));
            VideoEmbeddedMetadata.Payloads payloads =
                    VideoEmbeddedMetadata.encode(metadata, plan.durationUs);
            muxer = new MediaMuxer(
                    outputDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (plan.rotationDegrees != 0) {
                muxer.setOrientationHint(plan.rotationDegrees);
            }
            for (TrackState track : plan.tracks) {
                MediaFormat outputFormat = new MediaFormat(track.format);
                outputFormat.removeKey(MediaFormat.KEY_ROTATION);
                track.outputIndex = muxer.addTrack(outputFormat);
                extractor.selectTrack(track.sourceIndex);
            }
            int descriptionTrack = muxer.addTrack(metadataFormat(
                    VideoEmbeddedMetadata.DESCRIPTION_MIME,
                    payloads.description.length,
                    plan.durationUs));
            int subtitleTrack = -1;
            if (payloads.hasSubtitleTrack()) {
                subtitleTrack = muxer.addTrack(metadataFormat(
                        VideoEmbeddedMetadata.SUBTITLE_MIME,
                        VideoEmbeddedMetadata.MAX_SAMPLE_BYTES,
                        plan.durationUs));
            }

            muxer.start();
            muxerStarted = true;
            writeMetadataSample(muxer, descriptionTrack, 0L, payloads.description);
            CopyTotals totals = copySamples(
                    extractor,
                    muxer,
                    plan,
                    subtitleTrack,
                    payloads.subtitleEvents,
                    progress);
            writeEndOfStream(muxer, descriptionTrack, plan.durationUs);
            if (subtitleTrack >= 0
                    && payloads.subtitleEvents.get(payloads.subtitleEvents.size() - 1)
                    .presentationTimeUs < plan.durationUs) {
                writeEndOfStream(muxer, subtitleTrack, plan.durationUs);
            }
            muxer.stop();
            muxerStopped = true;
            muxer.release();
            muxer = null;
            outputDescriptor.getFileDescriptor().sync();

            verifyOutput(outputDescriptor, plan, payloads);
            if (progress != null) progress.onProgress(100);
            return new Result(
                    plan.tracks.size(),
                    totals.sampleCount,
                    totals.sampleBytes,
                    payloads.hasSubtitleTrack());
        } catch (RuntimeException error) {
            throw new IOException("cannot create enhanced MP4", error);
        } finally {
            extractor.release();
            retriever.release();
            if (muxer != null) {
                if (muxerStarted && !muxerStopped) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException ignored) {
                        // The unpublished output is deleted by the caller.
                    }
                }
                muxer.release();
            }
        }
    }

    private static SourcePlan inspectSource(MediaExtractor extractor, long containerDurationUs)
            throws IOException {
        int count = extractor.getTrackCount();
        if (count <= 0 || count > MAX_SOURCE_TRACKS) {
            throw new IOException("unsupported MP4 track count");
        }
        List<TrackState> tracks = new ArrayList<>(count);
        boolean sawVideo = false;
        long durationUs = containerDurationUs;
        int rotation = -1;
        for (int index = 0; index < count; index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!copyableMime(mime)
                    || VideoEmbeddedMetadata.DESCRIPTION_MIME.equals(mime)
                    || VideoEmbeddedMetadata.SUBTITLE_MIME.equals(mime)) {
                throw new IOException("MP4 contains an unsupported or existing AIOS track");
            }
            if (mime.startsWith("video/")) {
                sawVideo = true;
                int trackRotation = format.containsKey(MediaFormat.KEY_ROTATION)
                        ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
                if (trackRotation != 0 && trackRotation != 90
                        && trackRotation != 180 && trackRotation != 270) {
                    throw new IOException("MP4 contains invalid video rotation");
                }
                if (rotation >= 0 && rotation != trackRotation) {
                    throw new IOException("MP4 video tracks have conflicting rotation");
                }
                rotation = trackRotation;
            }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION));
            }
            tracks.add(new TrackState(index, format, formatFingerprint(format)));
        }
        if (!sawVideo || durationUs <= 0L
                || durationUs > VideoTranscript.MAX_TIMELINE_MILLIS * 1_000L) {
            throw new IOException("MP4 has no bounded video timeline");
        }
        return new SourcePlan(tracks, durationUs, Math.max(rotation, 0));
    }

    private static long containerDurationUs(MediaMetadataRetriever retriever)
            throws IOException {
        String value = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
        try {
            long durationMillis = value == null ? 0L : Long.parseLong(value);
            return Math.multiplyExact(durationMillis, 1_000L);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IOException("MP4 container duration is invalid", error);
        }
    }

    private static CopyTotals copySamples(
            MediaExtractor extractor,
            MediaMuxer muxer,
            SourcePlan plan,
            int subtitleTrack,
            List<VideoEmbeddedMetadata.Event> subtitleEvents,
            ProgressListener progress) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(INITIAL_SAMPLE_BUFFER_BYTES);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int eventIndex = 0;
        int sampleCount = 0;
        long sampleBytes = 0L;
        int lastProgress = -1;
        while (extractor.getSampleTrackIndex() >= 0) {
            int sourceTrack = extractor.getSampleTrackIndex();
            long presentationTimeUs = extractor.getSampleTime();
            if (presentationTimeUs < 0L) {
                throw new IOException("MP4 contains a negative sample timestamp");
            }
            while (subtitleTrack >= 0 && eventIndex < subtitleEvents.size()
                    && subtitleEvents.get(eventIndex).presentationTimeUs <= presentationTimeUs) {
                VideoEmbeddedMetadata.Event event = subtitleEvents.get(eventIndex++);
                writeMetadataSample(
                        muxer, subtitleTrack, event.presentationTimeUs, event.payload);
            }
            int sampleFlags = extractor.getSampleFlags();
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                throw new IOException("encrypted MP4 samples are not exportable");
            }
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                throw new IOException("partial MP4 samples are not exportable");
            }
            long expectedSize = extractor.getSampleSize();
            if (expectedSize < 0L || expectedSize > MAX_SAMPLE_BYTES) {
                throw new IOException("MP4 sample exceeds the copy bound");
            }
            if (expectedSize > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect((int) expectedSize);
            }
            buffer.clear();
            int read = extractor.readSampleData(buffer, 0);
            if (read < 0 || read != expectedSize) {
                throw new IOException("MP4 sample changed while copying");
            }
            int muxerFlags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            info.set(0, read, presentationTimeUs, muxerFlags);
            buffer.position(0);
            buffer.limit(read);
            TrackState track = plan.tracks.get(sourceTrack);
            track.sourceFingerprint.add(buffer, presentationTimeUs, muxerFlags);
            buffer.position(0);
            muxer.writeSampleData(track.outputIndex, buffer, info);
            sampleCount++;
            sampleBytes += read;

            int percent = (int) Math.min(90L, presentationTimeUs * 90L / plan.durationUs);
            if (progress != null && percent >= lastProgress + 5) {
                lastProgress = percent;
                progress.onProgress(percent);
            }
            extractor.advance();
        }
        while (subtitleTrack >= 0 && eventIndex < subtitleEvents.size()) {
            VideoEmbeddedMetadata.Event event = subtitleEvents.get(eventIndex++);
            writeMetadataSample(muxer, subtitleTrack, event.presentationTimeUs, event.payload);
        }
        return new CopyTotals(sampleCount, sampleBytes);
    }

    private static void verifyOutput(
            ParcelFileDescriptor outputDescriptor,
            SourcePlan source,
            VideoEmbeddedMetadata.Payloads payloads) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(outputDescriptor.getFileDescriptor());
            int expectedTracks = source.tracks.size() + 1 + (payloads.hasSubtitleTrack() ? 1 : 0);
            if (extractor.getTrackCount() != expectedTracks) {
                throw new IOException("enhanced MP4 track count mismatch");
            }
            for (int index = 0; index < source.tracks.size(); index++) {
                TrackState expected = source.tracks.get(index);
                MediaFormat actualFormat = extractor.getTrackFormat(index);
                if (!MessageDigest.isEqual(
                        expected.formatFingerprint, formatFingerprint(actualFormat))) {
                    throw new IOException("enhanced MP4 track format mismatch");
                }
                extractor.selectTrack(index);
            }
            int descriptionTrack = source.tracks.size();
            requireMime(extractor.getTrackFormat(descriptionTrack),
                    VideoEmbeddedMetadata.DESCRIPTION_MIME);
            extractor.selectTrack(descriptionTrack);
            int subtitleTrack = -1;
            if (payloads.hasSubtitleTrack()) {
                subtitleTrack = descriptionTrack + 1;
                requireMime(extractor.getTrackFormat(subtitleTrack),
                        VideoEmbeddedMetadata.SUBTITLE_MIME);
                extractor.selectTrack(subtitleTrack);
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(INITIAL_SAMPLE_BUFFER_BYTES);
            List<MetadataSample> descriptions = new ArrayList<>();
            List<MetadataSample> subtitles = new ArrayList<>();
            while (extractor.getSampleTrackIndex() >= 0) {
                int trackIndex = extractor.getSampleTrackIndex();
                long size = extractor.getSampleSize();
                if (size < 0L || size > MAX_SAMPLE_BYTES) {
                    throw new IOException("enhanced MP4 verification sample is invalid");
                }
                if (size > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect((int) size);
                }
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read != size) {
                    throw new IOException("enhanced MP4 verification read failed");
                }
                buffer.position(0);
                buffer.limit(read);
                if (trackIndex < source.tracks.size()) {
                    int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    source.tracks.get(trackIndex).outputFingerprint.add(
                            buffer, extractor.getSampleTime(), flags);
                } else {
                    byte[] value = new byte[read];
                    buffer.get(value);
                    MetadataSample sample = new MetadataSample(extractor.getSampleTime(), value);
                    if (trackIndex == descriptionTrack) {
                        descriptions.add(sample);
                    } else if (trackIndex == subtitleTrack) {
                        subtitles.add(sample);
                    } else {
                        throw new IOException("unexpected enhanced MP4 track");
                    }
                }
                extractor.advance();
            }
            for (TrackState track : source.tracks) {
                if (!track.sourceFingerprint.matches(track.outputFingerprint)) {
                    throw new IOException("encoded MP4 samples changed during remux");
                }
            }
            if (descriptions.size() != 1
                    || descriptions.get(0).presentationTimeUs != 0L
                    || !Arrays.equals(descriptions.get(0).payload, payloads.description)) {
                throw new IOException("enhanced MP4 description track mismatch");
            }
            if (subtitles.size() != payloads.subtitleEvents.size()) {
                throw new IOException("enhanced MP4 subtitle sample count mismatch");
            }
            for (int index = 0; index < subtitles.size(); index++) {
                MetadataSample actual = subtitles.get(index);
                VideoEmbeddedMetadata.Event expected = payloads.subtitleEvents.get(index);
                if (actual.presentationTimeUs != expected.presentationTimeUs
                        || !Arrays.equals(actual.payload, expected.payload)) {
                    throw new IOException("enhanced MP4 subtitle track mismatch");
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("cannot verify enhanced MP4", error);
        } finally {
            extractor.release();
        }
    }

    private static MediaFormat metadataFormat(String mime, int maxInputSize, long durationUs) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mime);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
        format.setLong(MediaFormat.KEY_DURATION, durationUs);
        return format;
    }

    private static void writeMetadataSample(
            MediaMuxer muxer, int track, long presentationTimeUs, byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, value.length, presentationTimeUs, 0);
        muxer.writeSampleData(track, buffer, info);
    }

    private static void writeEndOfStream(MediaMuxer muxer, int track, long durationUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(track, ByteBuffer.allocate(0), info);
    }

    private static void requireMime(MediaFormat format, String expected) throws IOException {
        if (!expected.equals(format.getString(MediaFormat.KEY_MIME))) {
            throw new IOException("enhanced MP4 metadata MIME mismatch");
        }
    }

    private static boolean copyableMime(String mime) {
        if (mime == null || mime.isBlank()) return false;
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true;
        if (!mime.startsWith("application/")) return false;
        return !MediaFormat.MIMETYPE_TEXT_SUBRIP.equals(mime)
                && !"application/ttml+xml".equals(mime);
    }

    private static byte[] formatFingerprint(MediaFormat format) throws IOException {
        MessageDigest digest = sha256();
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) throw new IOException("MP4 track MIME is absent");
        update(digest, mime.getBytes(StandardCharsets.UTF_8));
        for (String key : INTEGER_FORMAT_KEYS) {
            update(digest, key.getBytes(StandardCharsets.US_ASCII));
            long missing = MediaFormat.KEY_ROTATION.equals(key) ? 0L : Long.MIN_VALUE;
            updateLong(digest, format.containsKey(key) ? format.getInteger(key) : missing);
        }
        for (int index = 0; index < 8; index++) {
            String key = "csd-" + index;
            ByteBuffer value = format.containsKey(key) ? format.getByteBuffer(key) : null;
            update(digest, key.getBytes(StandardCharsets.US_ASCII));
            if (value == null) {
                updateLong(digest, -1L);
            } else {
                ByteBuffer copy = value.duplicate();
                updateLong(digest, copy.remaining());
                digest.update(copy);
            }
        }
        return digest.digest();
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, byte[] value) {
        updateLong(digest, value.length);
        digest.update(value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static final class SourcePlan {
        final List<TrackState> tracks;
        final long durationUs;
        final int rotationDegrees;

        SourcePlan(List<TrackState> tracks, long durationUs, int rotationDegrees) {
            this.tracks = tracks;
            this.durationUs = durationUs;
            this.rotationDegrees = rotationDegrees;
        }
    }

    private static final class TrackState {
        final int sourceIndex;
        final MediaFormat format;
        final byte[] formatFingerprint;
        final SampleFingerprint sourceFingerprint;
        final SampleFingerprint outputFingerprint;
        int outputIndex;

        TrackState(int sourceIndex, MediaFormat format, byte[] formatFingerprint)
                throws IOException {
            this.sourceIndex = sourceIndex;
            this.format = format;
            this.formatFingerprint = formatFingerprint;
            sourceFingerprint = new SampleFingerprint();
            outputFingerprint = new SampleFingerprint();
            outputIndex = -1;
        }
    }

    private static final class SampleFingerprint {
        final MessageDigest digest;
        int count;
        long bytes;

        SampleFingerprint() throws IOException {
            digest = sha256();
        }

        void add(ByteBuffer value, long presentationTimeUs, int flags) {
            ByteBuffer copy = value.duplicate();
            updateLong(digest, presentationTimeUs);
            updateLong(digest, flags);
            updateLong(digest, copy.remaining());
            bytes += copy.remaining();
            count++;
            digest.update(copy);
        }

        boolean matches(SampleFingerprint other) {
            return count == other.count && bytes == other.bytes
                    && MessageDigest.isEqual(digest.digest(), other.digest.digest());
        }
    }

    private static final class CopyTotals {
        final int sampleCount;
        final long sampleBytes;

        CopyTotals(int sampleCount, long sampleBytes) {
            this.sampleCount = sampleCount;
            this.sampleBytes = sampleBytes;
        }
    }

    private static final class MetadataSample {
        final long presentationTimeUs;
        final byte[] payload;

        MetadataSample(long presentationTimeUs, byte[] payload) {
            this.presentationTimeUs = presentationTimeUs;
            this.payload = payload;
        }
    }
}
