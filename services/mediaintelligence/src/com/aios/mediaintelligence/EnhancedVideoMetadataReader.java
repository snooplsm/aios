package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fail-closed parser for the two custom tracks authored by {@link VideoEnhancedCopyMuxer}. */
final class EnhancedVideoMetadataReader {
    static final class MediaRecord {
        final Uri uri;
        final long generation;

        MediaRecord(Uri uri, long generation) {
            this.uri = uri;
            this.generation = generation;
        }
    }

    static final class ParsedVideo {
        final long durationUs;
        final VideoEmbeddedMetadata.Data data;

        ParsedVideo(long durationUs, VideoEmbeddedMetadata.Data data) {
            this.durationUs = durationUs;
            this.data = data;
        }
    }

    private static final int INITIAL_BUFFER_BYTES = 64 * 1024;

    private EnhancedVideoMetadataReader() {}

    static MediaRecord resolve(ContentResolver resolver, String requested) throws IOException {
        if (resolver == null || requested == null || requested.length() > 512) {
            throw new IllegalArgumentException("canonical MediaStore video URI required");
        }
        Uri uri = Uri.parse(requested);
        Uri canonical;
        try {
            if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                    || !MediaStore.AUTHORITY.equals(uri.getAuthority())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("canonical MediaStore video URI required");
            }
            String volume = MediaStore.getVolumeName(uri);
            long id = ContentUris.parseId(uri);
            if (id <= 0L) throw new IllegalArgumentException("invalid MediaStore video ID");
            canonical = ContentUris.withAppendedId(
                    MediaStore.Video.Media.getContentUri(volume), id);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("canonical MediaStore video URI required", error);
        }

        String[] projection = {
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED,
        };
        try (Cursor cursor = resolver.query(canonical, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("enhanced video is unavailable");
            }
            long generation = cursor.getLong(0);
            String mimeType = cursor.getString(1);
            String owner = cursor.getString(2);
            int pending = cursor.getInt(3);
            int trashed = cursor.getInt(4);
            if (cursor.moveToNext()) {
                throw new IOException("MediaStore returned duplicate video rows");
            }
            EnhancedVideoReadPolicy.validateMediaRow(
                    requested,
                    canonical.toString(),
                    mimeType,
                    owner,
                    pending,
                    trashed,
                    generation);
            return new MediaRecord(canonical, generation);
        }
    }

    static ParsedVideo read(ContentResolver resolver, MediaRecord expected) throws IOException {
        if (resolver == null || expected == null) {
            throw new IllegalArgumentException("enhanced-video record required");
        }
        try (ParcelFileDescriptor descriptor =
                     resolver.openFileDescriptor(expected.uri, "r")) {
            if (descriptor == null) throw new FileNotFoundException("enhanced video unavailable");
            ParsedVideo result = readDescriptor(descriptor);
            MediaRecord after = resolve(resolver, expected.uri.toString());
            if (after.generation != expected.generation) {
                throw new IOException("enhanced video changed while reading");
            }
            return result;
        }
    }

    /** Package-visible for the platform mux/read emulator smoke lane. */
    static ParsedVideo readDescriptor(ParcelFileDescriptor descriptor) throws IOException {
        if (descriptor == null) throw new IllegalArgumentException("video descriptor required");
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(descriptor.getFileDescriptor());
            int trackCount = extractor.getTrackCount();
            int descriptionTrack = -1;
            int subtitleTrack = -1;
            int descriptionTracks = 0;
            int subtitleTracks = 0;
            boolean hasVideo = false;
            long durationUs = 0L;
            for (int index = 0; index < trackCount; index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (VideoEmbeddedMetadata.DESCRIPTION_MIME.equals(mime)) {
                    descriptionTracks++;
                    descriptionTrack = index;
                } else if (VideoEmbeddedMetadata.SUBTITLE_MIME.equals(mime)) {
                    subtitleTracks++;
                    subtitleTrack = index;
                } else if (mime != null && mime.startsWith("video/")) {
                    hasVideo = true;
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION));
                }
            }
            EnhancedVideoReadPolicy.validateContainer(
                    trackCount,
                    descriptionTracks,
                    subtitleTracks,
                    hasVideo,
                    durationUs);

            List<Sample> descriptions = readTrack(extractor, descriptionTrack, 1);
            if (descriptions.size() != 1 || descriptions.get(0).presentationTimeUs != 0L) {
                throw new IOException("invalid enhanced-video description track");
            }
            List<Sample> subtitleSamples = subtitleTrack < 0
                    ? List.of()
                    : readTrack(
                            extractor,
                            subtitleTrack,
                            EnhancedVideoReadPolicy.MAX_EVENT_SAMPLES);
            long totalBytes = descriptions.get(0).payload.length;
            for (Sample sample : subtitleSamples) {
                totalBytes += sample.payload.length;
                if (totalBytes > EnhancedVideoReadPolicy.MAX_TOTAL_METADATA_BYTES) {
                    throw new IOException("enhanced-video metadata exceeds its total bound");
                }
            }
            return parseCanonical(
                    descriptions.get(0).payload,
                    subtitleTrack >= 0,
                    subtitleSamples,
                    durationUs);
        } catch (IllegalArgumentException | JSONException error) {
            throw new IOException("invalid embedded AIOS video metadata", error);
        } catch (RuntimeException error) {
            throw new IOException("cannot read enhanced MP4", error);
        } finally {
            extractor.release();
        }
    }

    private static List<Sample> readTrack(
            MediaExtractor extractor, int trackIndex, int maxSamples) throws IOException {
        extractor.selectTrack(trackIndex);
        try {
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_BYTES);
            List<Sample> result = new ArrayList<>();
            while (extractor.getSampleTrackIndex() >= 0) {
                if (extractor.getSampleTrackIndex() != trackIndex
                        || result.size() >= maxSamples) {
                    throw new IOException("enhanced-video metadata sample count is invalid");
                }
                long size = extractor.getSampleSize();
                long timeUs = extractor.getSampleTime();
                if (size <= 0L || size > VideoEmbeddedMetadata.MAX_SAMPLE_BYTES
                        || timeUs < 0L) {
                    throw new IOException("enhanced-video metadata sample is invalid");
                }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read != size) throw new IOException("enhanced-video metadata read failed");
                byte[] payload = new byte[read];
                buffer.position(0);
                buffer.limit(read);
                buffer.get(payload);
                result.add(new Sample(timeUs, payload));
                if (!extractor.advance() && extractor.getSampleTrackIndex() >= 0) {
                    throw new IOException("enhanced-video extractor did not advance");
                }
            }
            return result;
        } finally {
            extractor.unselectTrack(trackIndex);
        }
    }

    private static ParsedVideo parseCanonical(
            byte[] description,
            boolean hasSubtitleTrack,
            List<Sample> subtitleSamples,
            long durationUs) throws JSONException, IOException {
        JSONObject value = object(description);
        require(value.getInt("schema_version") == 1
                && "description".equals(value.getString("type")),
                "unsupported enhanced-video description schema");

        JSONArray tagValues = value.getJSONArray("tags");
        List<String> tags = new ArrayList<>(tagValues.length());
        for (int index = 0; index < tagValues.length(); index++) {
            tags.add(tagValues.getString(index));
        }
        List<VideoEmbeddedMetadata.Cue> cues = new ArrayList<>();
        for (Sample sample : subtitleSamples) {
            JSONObject event = object(sample.payload);
            require(event.getInt("schema_version") == 1,
                    "unsupported enhanced-video subtitle schema");
            if ("cue".equals(event.getString("type"))) {
                long startUs = event.getLong("start_us");
                long endUs = event.getLong("end_us");
                require(startUs % 1_000L == 0L && endUs % 1_000L == 0L,
                        "subtitle timestamps are not millisecond aligned");
                cues.add(new VideoEmbeddedMetadata.Cue(
                        event.getInt("sequence"),
                        event.getString("language"),
                        startUs / 1_000L,
                        endUs / 1_000L,
                        event.getString("text"),
                        (float) event.getDouble("confidence")));
            } else {
                require("clear".equals(event.getString("type")),
                        "unknown enhanced-video subtitle event");
            }
        }

        VideoEmbeddedMetadata.Data data = new VideoEmbeddedMetadata.Data(
                value.getLong("source_generation"),
                value.getString("source_sha256"),
                value.getString("caption"),
                tags,
                value.getString("language"),
                (float) value.getDouble("confidence"),
                value.getString("vision_model_id"),
                value.getString("vision_model_sha256"),
                value.getLong("inferred_at_epoch_ms"),
                value.getString("subtitle_status"),
                value.getString("asr_model_id"),
                value.getString("asr_model_sha256"),
                value.getString("subtitle_language"),
                cues);
        VideoEmbeddedMetadata.Payloads canonical =
                VideoEmbeddedMetadata.encode(data, durationUs);
        require(Arrays.equals(description, canonical.description),
                "noncanonical enhanced-video description");
        require(hasSubtitleTrack == canonical.hasSubtitleTrack()
                        && subtitleSamples.size() == canonical.subtitleEvents.size(),
                "enhanced-video subtitle track does not match its description");
        for (int index = 0; index < subtitleSamples.size(); index++) {
            Sample actual = subtitleSamples.get(index);
            VideoEmbeddedMetadata.Event expected = canonical.subtitleEvents.get(index);
            require(actual.presentationTimeUs == expected.presentationTimeUs
                            && Arrays.equals(actual.payload, expected.payload),
                    "noncanonical enhanced-video subtitle event");
        }
        return new ParsedVideo(durationUs, data);
    }

    private static JSONObject object(byte[] value) throws JSONException {
        return new JSONObject(new String(value, StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static final class Sample {
        final long presentationTimeUs;
        final byte[] payload;

        Sample(long presentationTimeUs, byte[] payload) {
            this.presentationTimeUs = presentationTimeUs;
            this.payload = payload;
        }
    }
}
