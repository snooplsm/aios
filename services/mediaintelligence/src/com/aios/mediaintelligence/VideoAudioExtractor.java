package com.aios.mediaintelligence;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streams a video's complete primary audio timeline as bounded PCM16 mono at 16 kHz. */
final class VideoAudioExtractor {
    static final int OUTPUT_SAMPLE_RATE_HZ = 16_000;
    private static final long CODEC_TIMEOUT_MICROS = 10_000L;
    private static final long MAX_AUDIO_DURATION_MICROS =
            VideoTranscript.MAX_TIMELINE_MILLIS * 1_000L;
    private static final int MAX_DECODED_BUFFER_BYTES = 16 * 1024 * 1024;
    private static final int WRITE_BUFFER_BYTES = 32 * 1024;

    static final class Result {
        final boolean hasAudio;
        final long timelineOffsetMillis;
        final long decodedDurationMillis;

        Result(boolean hasAudio, long timelineOffsetMillis, long decodedDurationMillis) {
            this.hasAudio = hasAudio;
            this.timelineOffsetMillis = timelineOffsetMillis;
            this.decodedDurationMillis = decodedDurationMillis;
        }
    }

    private VideoAudioExtractor() {}

    static Result stream(
            Context context,
            Uri uri,
            OutputStream sink,
            MediaConstraintProbe constraints) throws IOException, InterruptedException {
        if (context == null || uri == null || sink == null || constraints == null) {
            throw new IllegalArgumentException("video audio input is absent");
        }
        requireAvailable(constraints);
        AssetFileDescriptor source = context.getContentResolver()
                .openAssetFileDescriptor(uri, "r");
        if (source == null) throw new FileNotFoundException("video is unavailable");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean decoderStarted = false;
        try (source) {
            if (source.getLength() >= 0L) {
                extractor.setDataSource(
                        source.getFileDescriptor(), source.getStartOffset(), source.getLength());
            } else {
                extractor.setDataSource(source.getFileDescriptor());
            }
            int track = primaryAudioTrack(extractor);
            if (track < 0) return new Result(false, 0L, 0L);
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            validateDuration(inputFormat);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) {
                throw new VideoStoryboard.InvalidVideoException(
                        "video primary audio type is invalid");
            }
            extractor.selectTrack(track);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            decoderStarted = true;
            return decode(extractor, decoder, inputFormat, sink, constraints);
        } catch (VideoStoryboard.BlockedException
                | VideoStoryboard.InvalidVideoException error) {
            throw error;
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new VideoStoryboard.InvalidVideoException(
                    "video primary audio cannot be decoded");
        } finally {
            if (decoder != null) {
                if (decoderStarted) {
                    try {
                        decoder.stop();
                    } catch (RuntimeException ignored) {
                        // Release below remains mandatory after codec failure.
                    }
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static Result decode(
            MediaExtractor extractor,
            MediaCodec decoder,
            MediaFormat initialFormat,
            OutputStream sink,
            MediaConstraintProbe constraints) throws IOException, InterruptedException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        PcmTimelineWriter writer = new PcmTimelineWriter(sink, constraints);
        MediaFormat outputFormat = initialFormat;
        boolean inputEnded = false;
        boolean outputEnded = false;
        while (!outputEnded) {
            requireNotInterrupted();
            requireAvailable(constraints);
            if (!inputEnded) {
                int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS);
                if (inputIndex >= 0) {
                    ByteBuffer input = decoder.getInputBuffer(inputIndex);
                    if (input == null) {
                        throw new VideoStoryboard.InvalidVideoException(
                                "video audio decoder input is absent");
                    }
                    input.clear();
                    int size = extractor.readSampleData(input, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEnded = true;
                    } else {
                        long presentationTime = extractor.getSampleTime();
                        if (presentationTime < 0L
                                || presentationTime > MAX_AUDIO_DURATION_MICROS) {
                            throw new VideoStoryboard.InvalidVideoException(
                                    "video audio timestamp is invalid");
                        }
                        decoder.queueInputBuffer(inputIndex, 0, size, presentationTime, 0);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_MICROS);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = decoder.getOutputFormat();
            } else if (outputIndex >= 0) {
                try {
                    if (info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                        if (output == null) {
                            throw new VideoStoryboard.InvalidVideoException(
                                    "video audio decoder output is absent");
                        }
                        writer.write(output, info, outputFormat);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally {
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        }
        sink.flush();
        return writer.result();
    }

    private static int primaryAudioTrack(MediaExtractor extractor) {
        int first = -1;
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) continue;
            if (first < 0) first = index;
            if (format.containsKey(MediaFormat.KEY_IS_DEFAULT)
                    && format.getInteger(MediaFormat.KEY_IS_DEFAULT) == 1) {
                return index;
            }
        }
        return first;
    }

    private static void validateDuration(MediaFormat format)
            throws VideoStoryboard.InvalidVideoException {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            long duration = format.getLong(MediaFormat.KEY_DURATION);
            if (duration <= 0L || duration > MAX_AUDIO_DURATION_MICROS) {
                throw new VideoStoryboard.InvalidVideoException(
                        "video audio duration is invalid");
            }
        }
    }

    private static final class PcmTimelineWriter {
        private final OutputStream sink;
        private final MediaConstraintProbe constraints;
        private long firstPresentationMicros = -1L;
        private long writtenFrames;
        private int sourceSampleRate;
        private int sourceChannels;
        private int sourceEncoding;

        PcmTimelineWriter(OutputStream sink, MediaConstraintProbe constraints) {
            this.sink = sink;
            this.constraints = constraints;
        }

        void write(ByteBuffer source, MediaCodec.BufferInfo info, MediaFormat format)
                throws IOException, InterruptedException {
            int sampleRate = requiredInteger(format, MediaFormat.KEY_SAMPLE_RATE);
            int channels = requiredInteger(format, MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    : AudioFormat.ENCODING_PCM_16BIT;
            if (sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 8
                    || !(encoding == AudioFormat.ENCODING_PCM_16BIT
                    || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                    || info.size < 1 || info.size > MAX_DECODED_BUFFER_BYTES) {
                throw new VideoStoryboard.InvalidVideoException(
                        "decoded video audio format is unsupported");
            }
            if (sourceSampleRate == 0) {
                sourceSampleRate = sampleRate;
                sourceChannels = channels;
                sourceEncoding = encoding;
            } else if (sourceSampleRate != sampleRate || sourceChannels != channels
                    || sourceEncoding != encoding) {
                throw new VideoStoryboard.InvalidVideoException(
                        "decoded video audio format changed");
            }
            int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            int bytesPerFrame = bytesPerSample * channels;
            if (info.size % bytesPerFrame != 0 || info.offset < 0
                    || info.offset > source.capacity() - info.size) {
                throw new VideoStoryboard.InvalidVideoException(
                        "decoded video audio buffer is malformed");
            }
            if (info.presentationTimeUs < 0L
                    || info.presentationTimeUs > MAX_AUDIO_DURATION_MICROS) {
                throw new VideoStoryboard.InvalidVideoException(
                        "decoded video audio timestamp is invalid");
            }
            if (firstPresentationMicros < 0L) firstPresentationMicros = info.presentationTimeUs;
            long relativeMicros = info.presentationTimeUs - firstPresentationMicros;
            if (relativeMicros < 0L) {
                throw new VideoStoryboard.InvalidVideoException(
                        "decoded video audio timeline moved backwards");
            }

            ByteBuffer input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            input.position(info.offset);
            input.limit(info.offset + info.size);
            int sourceFrames = info.size / bytesPerFrame;
            float[] mono = new float[sourceFrames];
            for (int frame = 0; frame < sourceFrames; frame++) {
                float sum = 0.0f;
                for (int channel = 0; channel < channels; channel++) {
                    float sample = encoding == AudioFormat.ENCODING_PCM_FLOAT
                            ? input.getFloat() : input.getShort() / 32768.0f;
                    if (!Float.isFinite(sample)) {
                        throw new VideoStoryboard.InvalidVideoException(
                                "decoded video audio contains a non-finite sample");
                    }
                    sum += sample;
                }
                mono[frame] = Math.max(-1.0f, Math.min(1.0f, sum / channels));
            }
            short[] resampled = resample(mono, sampleRate);
            long targetStartFrame = relativeMicros * OUTPUT_SAMPLE_RATE_HZ / 1_000_000L;
            if (targetStartFrame > writtenFrames) {
                writeSilence(targetStartFrame - writtenFrames);
            }
            int skip = targetStartFrame < writtenFrames
                    ? (int) Math.min((long) resampled.length, writtenFrames - targetStartFrame)
                    : 0;
            writeSamples(resampled, skip);
        }

        Result result() {
            if (firstPresentationMicros < 0L) return new Result(true, 0L, 0L);
            return new Result(
                    true,
                    firstPresentationMicros / 1_000L,
                    writtenFrames * 1_000L / OUTPUT_SAMPLE_RATE_HZ);
        }

        private short[] resample(float[] input, int sampleRate) {
            if (input.length == 0) return new short[0];
            int outputFrames = Math.max(
                    1, (int) (((long) input.length * OUTPUT_SAMPLE_RATE_HZ) / sampleRate));
            short[] output = new short[outputFrames];
            if (input.length == 1 || outputFrames == 1) {
                short value = pcm16(input[0]);
                java.util.Arrays.fill(output, value);
                return output;
            }
            double scale = (double) (input.length - 1) / (outputFrames - 1);
            for (int index = 0; index < outputFrames; index++) {
                double position = index * scale;
                int left = (int) position;
                int right = Math.min(input.length - 1, left + 1);
                float value = (float) (input[left]
                        + (input[right] - input[left]) * (position - left));
                output[index] = pcm16(value);
            }
            return output;
        }

        private void writeSilence(long frames) throws IOException, InterruptedException {
            byte[] zeros = new byte[WRITE_BUFFER_BYTES];
            long bytes = Math.multiplyExact(frames, 2L);
            while (bytes > 0L) {
                requireNotInterrupted();
                requireAvailable(constraints);
                int count = (int) Math.min(bytes, zeros.length);
                sink.write(zeros, 0, count);
                bytes -= count;
            }
            writtenFrames += frames;
        }

        private void writeSamples(short[] samples, int skip)
                throws IOException, InterruptedException {
            byte[] bytes = new byte[WRITE_BUFFER_BYTES];
            int index = skip;
            while (index < samples.length) {
                requireNotInterrupted();
                requireAvailable(constraints);
                int count = Math.min(samples.length - index, bytes.length / 2);
                for (int offset = 0; offset < count; offset++) {
                    short sample = samples[index + offset];
                    bytes[offset * 2] = (byte) (sample & 0xff);
                    bytes[offset * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
                }
                sink.write(bytes, 0, count * 2);
                index += count;
                writtenFrames += count;
            }
        }

        private static short pcm16(float value) {
            int scaled = Math.round(Math.max(-1.0f, Math.min(1.0f, value)) * 32767.0f);
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    private static int requiredInteger(MediaFormat format, String key)
            throws VideoStoryboard.InvalidVideoException {
        if (!format.containsKey(key)) {
            throw new VideoStoryboard.InvalidVideoException(
                    "decoded video audio format is incomplete");
        }
        return format.getInteger(key);
    }

    private static void requireAvailable(MediaConstraintProbe constraints)
            throws VideoStoryboard.BlockedException {
        String reason = constraints.blockedReason();
        if (reason != null) throw new VideoStoryboard.BlockedException(reason);
    }

    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("video audio extraction interrupted");
        }
    }
}
