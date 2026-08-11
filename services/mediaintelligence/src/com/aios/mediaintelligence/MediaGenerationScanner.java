package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reconciles durable jobs from MediaStore generations missed by ContentObserver. */
final class MediaGenerationScanner {
    private static final String TAG = "AiosMediaGeneration";
    private static final int MAX_ROWS_PER_VOLUME = 512;

    private MediaGenerationScanner() {}

    /** Establishes only missing/invalid volume cursors; never enqueues media. */
    static void establishBaselines(Context context, MediaJobStore store) {
        for (String volumeName : externalVolumes(context)) {
            try {
                String version = MediaStore.getVersion(context, volumeName);
                long currentGeneration = MediaStore.getGeneration(context, volumeName);
                establishBaselineIfRequired(
                        context, store, volumeName, version, currentGeneration,
                        store.scanState(volumeName));
            } catch (RuntimeException error) {
                // The delayed full reconciliation retries inaccessible volumes.
                Log.w(TAG, "cannot establish MediaStore volume baseline", error);
            }
        }
    }

    static ScanResult reconcile(Context context, MediaJobStore store) {
        boolean immediate = false;
        boolean deferred = false;
        boolean more = false;
        boolean retry = false;
        for (String volumeName : externalVolumes(context)) {
            try {
                VolumeResult result = reconcileVolume(context, store, volumeName);
                immediate |= result.immediate;
                deferred |= result.deferred;
                more |= result.more;
            } catch (RuntimeException error) {
                retry = true;
                Log.w(TAG, "cannot reconcile MediaStore volume", error);
            }
        }
        return new ScanResult(immediate, deferred, more, retry);
    }

    static Set<String> externalVolumes(Context context) {
        TreeSet<String> volumes = new TreeSet<>();
        try {
            volumes.addAll(MediaStore.getExternalVolumeNames(context));
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot enumerate MediaStore volumes", error);
        }
        volumes.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return volumes;
    }

    private static VolumeResult reconcileVolume(
            Context context, MediaJobStore store, String volumeName) {
        String version = MediaStore.getVersion(context, volumeName);
        long currentGeneration = MediaStore.getGeneration(context, volumeName);
        MediaJobStore.ScanState state = store.scanState(volumeName);
        if (establishBaselineIfRequired(
                context, store, volumeName, version, currentGeneration, state)) {
            return VolumeResult.EMPTY;
        }

        ArrayList<MediaGenerationReconciler.Row> rows = queryRows(
                context.getContentResolver(), volumeName, state.generation, state.mediaId);
        boolean truncated = rows.size() == MAX_ROWS_PER_VOLUME;
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                new MediaGenerationReconciler.CursorPoint(state.generation, state.mediaId),
                currentGeneration,
                rows,
                truncated);

        ArrayList<MediaGenerationReconciler.Row> unsuppressed = new ArrayList<>();
        for (MediaGenerationReconciler.Row row : plan.ready) {
            if (!store.shouldSuppressOwnMutation(row.uri, row.generationModified)) {
                unsuppressed.add(row);
            }
        }
        boolean immediate = false;
        boolean deferred = false;
        ArrayList<MediaCaptureGrouping.Item> captureItems = new ArrayList<>();
        for (MediaGenerationReconciler.Row row : unsuppressed) {
            captureItems.add(new MediaCaptureGrouping.Item(
                    row.uri, row.mimeType, row.observedAtEpochMillis));
        }
        Map<String, Integer> workClasses = MediaCaptureGrouping.classify(
                captureItems,
                state.mediaId != MediaGenerationReconciler.END_OF_GENERATION,
                plan.more || plan.blockedByPendingItem);
        for (MediaGenerationReconciler.Row row : unsuppressed) {
            ObservedMedia media = new ObservedMedia(
                    row.uri,
                    row.generationModified,
                    row.mimeType,
                    row.observedAtEpochMillis);
            int workClass = workClasses.get(row.uri);
            store.enqueue(media, workClass);
            immediate |= workClass == MediaWorkPolicy.CLASS_IMMEDIATE;
            deferred |= workClass == MediaWorkPolicy.CLASS_DEFERRED;
        }
        store.writeScanState(
                volumeName, version, plan.next.generation, plan.next.mediaId);
        return new VolumeResult(immediate, deferred, plan.more);
    }

    private static boolean establishBaselineIfRequired(
            Context context,
            MediaJobStore store,
            String volumeName,
            String version,
            long currentGeneration,
            MediaJobStore.ScanState state) {
        if (!MediaGenerationBaselinePolicy.requiresBaseline(
                version,
                currentGeneration,
                state != null,
                state == null ? null : state.mediaStoreVersion,
                state == null ? 0L : state.generation)) {
            return false;
        }
        // Installing AIOS or a provider-database rebuild must not enqueue the
        // owner's entire historical library. An existing cursor whose provider
        // identity changed cannot safely retain URI-keyed results.
        if (state != null && store.purgeVolume(volumeName)) {
            MediaContextAssociationService.requestReconcile(context);
        }
        store.writeScanState(
                volumeName,
                version,
                currentGeneration,
                MediaGenerationReconciler.END_OF_GENERATION);
        return true;
    }

    private static ArrayList<MediaGenerationReconciler.Row> queryRows(
            ContentResolver resolver,
            String volumeName,
            long checkpointGeneration,
            long checkpointMediaId) {
        Uri files = MediaStore.Files.getContentUri(volumeName);
        String generationAdded = MediaStore.MediaColumns.GENERATION_ADDED;
        String mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE;
        Bundle arguments = new Bundle();
        arguments.putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(" + generationAdded + ">? OR (" + generationAdded + "=? AND "
                        + MediaStore.MediaColumns._ID + ">?)) AND ("
                        + mediaType + "=? OR " + mediaType + "=?)");
        arguments.putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{
                        Long.toString(checkpointGeneration),
                        Long.toString(checkpointGeneration),
                        Long.toString(checkpointMediaId),
                        Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                        Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                });
        arguments.putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{generationAdded, MediaStore.MediaColumns._ID});
        arguments.putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING);
        arguments.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS_PER_VOLUME);

        ArrayList<MediaGenerationReconciler.Row> rows = new ArrayList<>();
        try (Cursor cursor = resolver.query(
                files,
                new String[]{
                        MediaStore.MediaColumns._ID,
                        generationAdded,
                        MediaStore.MediaColumns.GENERATION_MODIFIED,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                        mediaType,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.IS_TRASHED
                },
                arguments,
                null)) {
            if (cursor == null) throw new IllegalStateException("MediaStore query returned no cursor");
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long added = cursor.getLong(1);
                long modified = cursor.getLong(2);
                boolean pending = cursor.getInt(3) != 0;
                String mime = cursor.getString(4);
                long size = cursor.getLong(5);
                int type = cursor.getInt(6);
                long observedAt = epochMillis(cursor.getLong(7));
                boolean trashed = cursor.getInt(8) != 0;
                Uri base = type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                        ? MediaStore.Images.Media.getContentUri(volumeName)
                        : MediaStore.Video.Media.getContentUri(volumeName);
                boolean eligible = !trashed && id > 0L && size > 0L && mime != null
                        && (mime.startsWith("image/") || mime.startsWith("video/"));
                rows.add(new MediaGenerationReconciler.Row(
                        id,
                        ContentUris.withAppendedId(base, id).toString(),
                        added,
                        modified,
                        mime == null ? "application/octet-stream" : mime,
                        observedAt,
                        pending,
                        eligible));
            }
        }
        return rows;
    }

    private static long epochMillis(long epochSeconds) {
        if (epochSeconds <= 0L) return System.currentTimeMillis();
        if (epochSeconds > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
        return epochSeconds * 1_000L;
    }

    static final class ScanResult {
        final boolean immediate;
        final boolean deferred;
        final boolean more;
        final boolean retry;

        ScanResult(boolean immediate, boolean deferred, boolean more, boolean retry) {
            this.immediate = immediate;
            this.deferred = deferred;
            this.more = more;
            this.retry = retry;
        }
    }

    private static final class VolumeResult {
        static final VolumeResult EMPTY = new VolumeResult(false, false, false);
        final boolean immediate;
        final boolean deferred;
        final boolean more;

        VolumeResult(boolean immediate, boolean deferred, boolean more) {
            this.immediate = immediate;
            this.deferred = deferred;
            this.more = more;
        }
    }
}
