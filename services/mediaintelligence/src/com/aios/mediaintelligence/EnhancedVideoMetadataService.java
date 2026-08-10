package com.aios.mediaintelligence;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.aios.media.EnhancedVideoCue;
import com.aios.media.EnhancedVideoInfo;
import com.aios.media.IEnhancedVideoMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Signature-only paged reader for metadata inside published AIOS-enhanced MP4s. */
public final class EnhancedVideoMetadataService extends Service {
    static final String ACTION = "com.aios.media.ENHANCED_VIDEO_METADATA_SERVICE";
    static final String PERMISSION = "com.aios.permission.READ_ENHANCED_VIDEO_METADATA";
    private final Object cacheLock = new Object();
    private EnhancedVideoMetadataReader.MediaRecord cachedRecord;
    private EnhancedVideoMetadataReader.ParsedVideo cachedVideo;

    private final IEnhancedVideoMetadata.Stub binder = new IEnhancedVideoMetadata.Stub() {
        @Override
        public EnhancedVideoInfo getInfo(String mediaUri) {
            enforceCaller();
            long token = Binder.clearCallingIdentity();
            try {
                Loaded loaded = load(mediaUri, -1L);
                return info(loaded.record, loaded.video);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<EnhancedVideoCue> getCues(
                String mediaUri,
                long expectedMediaGeneration,
                int startSequence,
                int limit) {
            enforceCaller();
            long token = Binder.clearCallingIdentity();
            try {
                Loaded loaded = load(mediaUri, expectedMediaGeneration);
                List<VideoEmbeddedMetadata.Cue> source = loaded.video.data.cues;
                int end = EnhancedVideoReadPolicy.pageEnd(
                        startSequence, limit, source.size());
                List<EnhancedVideoCue> result = new ArrayList<>(end - startSequence);
                for (int index = startSequence; index < end; index++) {
                    result.add(cue(source.get(index)));
                }
                return result;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) ? binder : null;
    }

    @Override
    public void onDestroy() {
        synchronized (cacheLock) {
            cachedRecord = null;
            cachedVideo = null;
        }
        super.onDestroy();
    }

    private Loaded load(String mediaUri, long expectedGeneration) {
        try {
            EnhancedVideoMetadataReader.MediaRecord record =
                    EnhancedVideoMetadataReader.resolve(getContentResolver(), mediaUri);
            if (expectedGeneration >= 0L && expectedGeneration != record.generation) {
                throw new IllegalStateException(
                        "enhanced video changed; request its info again");
            }
            synchronized (cacheLock) {
                if (cachedRecord != null
                        && cachedRecord.generation == record.generation
                        && cachedRecord.uri.equals(record.uri)) {
                    return new Loaded(record, cachedVideo);
                }
                EnhancedVideoMetadataReader.ParsedVideo parsed =
                        EnhancedVideoMetadataReader.read(getContentResolver(), record);
                cachedRecord = record;
                cachedVideo = parsed;
                return new Loaded(record, parsed);
            }
        } catch (IOException error) {
            throw new IllegalStateException("enhanced-video metadata is unavailable", error);
        }
    }

    private void enforceCaller() {
        enforceCallingOrSelfPermission(PERMISSION, "enhanced-video metadata access denied");
    }

    private static EnhancedVideoInfo info(
            EnhancedVideoMetadataReader.MediaRecord record,
            EnhancedVideoMetadataReader.ParsedVideo parsed) {
        VideoEmbeddedMetadata.Data source = parsed.data;
        EnhancedVideoInfo value = new EnhancedVideoInfo();
        value.schemaVersion = 1;
        value.mediaGeneration = record.generation;
        value.durationUs = parsed.durationUs;
        value.sourceGeneration = source.sourceGeneration;
        value.sourceSha256 = source.sourceDigest;
        value.caption = source.caption;
        value.tags = source.tags.toArray(String[]::new);
        value.language = source.language;
        value.confidence = source.confidence;
        value.visionModelId = source.visionModelId;
        value.visionModelSha256 = source.visionModelDigest;
        value.inferredAtEpochMillis = source.inferredAtEpochMillis;
        value.subtitleStatus = source.audioStatus;
        value.subtitleLanguage = source.audioLanguage;
        value.subtitleCueCount = source.cues.size();
        value.asrModelId = source.audioModelId;
        value.asrModelSha256 = source.audioModelDigest;
        return value;
    }

    private static EnhancedVideoCue cue(VideoEmbeddedMetadata.Cue source) {
        EnhancedVideoCue value = new EnhancedVideoCue();
        value.sequence = source.sequence;
        value.language = source.language;
        value.startUs = source.startMillis * 1_000L;
        value.endUs = source.endMillis * 1_000L;
        value.text = source.text;
        value.confidence = source.confidence;
        return value;
    }

    private record Loaded(
            EnhancedVideoMetadataReader.MediaRecord record,
            EnhancedVideoMetadataReader.ParsedVideo video) {}
}
