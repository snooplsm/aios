package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes index rows whose canonical MediaStore source no longer exists. */
final class MediaLivenessScanner {
    private static final String TAG = "AiosMediaLiveness";
    private static final int MAX_ROWS = 128;
    private static final Pattern VOLUME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private MediaLivenessScanner() {}

    static Result reconcile(Context context, MediaJobStore store, long afterJobId) {
        List<MediaJobStore.SourceRef> sources = store.sourceBatch(afterJobId, MAX_ROWS);
        boolean truncated = sources.size() == MAX_ROWS;
        List<MediaLivenessReconciler.Row> rows = new ArrayList<>();
        Map<String, Set<Long>> requestedIds = new HashMap<>();
        for (MediaJobStore.SourceRef source : sources) {
            ParsedSource parsed = parse(source.uri);
            if (parsed == null) {
                rows.add(new MediaLivenessReconciler.Row(
                        source.jobId, source.uri, null, 0L, false));
                continue;
            }
            rows.add(new MediaLivenessReconciler.Row(
                    source.jobId, source.uri, parsed.volumeName, parsed.mediaId, true));
            requestedIds.computeIfAbsent(parsed.volumeName, ignored -> new HashSet<>())
                    .add(parsed.mediaId);
        }

        Set<String> mounted = new HashSet<>(MediaGenerationScanner.externalVolumes(context));
        mounted.add(MediaStore.VOLUME_EXTERNAL);
        Map<String, Set<Long>> presentIds = new HashMap<>();
        Set<String> completedVolumes = new HashSet<>();
        boolean retry = false;
        for (Map.Entry<String, Set<Long>> request : requestedIds.entrySet()) {
            String volumeName = request.getKey();
            if (!mounted.contains(volumeName)) continue;
            try {
                Set<Long> present = queryPresentIds(context, volumeName, request.getValue());
                if (present == null) {
                    retry = true;
                    continue;
                }
                presentIds.put(volumeName, present);
                completedVolumes.add(volumeName);
            } catch (RuntimeException error) {
                retry = true;
                Log.w(TAG, "cannot verify MediaStore source liveness", error);
            }
        }

        MediaLivenessReconciler.Plan plan = MediaLivenessReconciler.plan(
                rows, presentIds, completedVolumes, truncated);
        boolean associationDeleted = false;
        for (String deletedUri : plan.deletedUris) {
            associationDeleted |= store.deleteMediaUri(deletedUri);
        }
        if (associationDeleted) MediaContextAssociationService.requestReconcile(context);
        return new Result(plan.nextJobId, plan.more, retry, plan.deletedUris.size());
    }

    static String mountedCanonicalItemUri(Context context, Uri uri) {
        ParsedSource parsed = parse(uri == null ? null : uri.toString());
        if (parsed == null) return null;
        Set<String> mounted = new HashSet<>(MediaGenerationScanner.externalVolumes(context));
        mounted.add(MediaStore.VOLUME_EXTERNAL);
        if (!mounted.contains(parsed.volumeName)) return null;
        Uri collection = "images".equals(parsed.collection)
                ? MediaStore.Images.Media.getContentUri(parsed.volumeName)
                : MediaStore.Video.Media.getContentUri(parsed.volumeName);
        return Uri.withAppendedPath(collection, Long.toString(parsed.mediaId)).toString();
    }

    static boolean reconcileExact(Context context, MediaJobStore store, Uri notificationUri) {
        String canonical = mountedCanonicalItemUri(context, notificationUri);
        if (canonical == null) return false;
        ParsedSource parsed = parse(canonical);
        if (parsed == null) return false;
        String identityVolume = MediaStore.VOLUME_EXTERNAL.equals(parsed.volumeName)
                ? MediaStore.VOLUME_EXTERNAL_PRIMARY : parsed.volumeName;
        String versionBefore = MediaStore.getVersion(context, identityVolume);
        long generationBefore = MediaStore.getGeneration(context, identityVolume);
        boolean remove;
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse(canonical),
                new String[]{MediaStore.MediaColumns.IS_TRASHED},
                null,
                null,
                null)) {
            if (cursor == null) return true;
            remove = !cursor.moveToFirst() || cursor.getInt(0) != 0;
        }
        String versionAfter = MediaStore.getVersion(context, identityVolume);
        long generationAfter = MediaStore.getGeneration(context, identityVolume);
        if (!versionBefore.equals(versionAfter) || generationBefore != generationAfter) {
            return true;
        }
        if (remove && store.deleteMediaUri(canonical)) {
            MediaContextAssociationService.requestReconcile(context);
        }
        return false;
    }

    private static Set<Long> queryPresentIds(
            Context context, String volumeName, Set<Long> requestedIds) {
        if (requestedIds.isEmpty()) return Set.of();
        String identityVolume = MediaStore.VOLUME_EXTERNAL.equals(volumeName)
                ? MediaStore.VOLUME_EXTERNAL_PRIMARY : volumeName;
        String versionBefore = MediaStore.getVersion(context, identityVolume);
        long generationBefore = MediaStore.getGeneration(context, identityVolume);
        String placeholders = String.join(",", java.util.Collections.nCopies(
                requestedIds.size(), "?"));
        String[] arguments = requestedIds.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        Set<Long> present = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri(volumeName),
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns._ID + " IN (" + placeholders + ") AND "
                        + MediaStore.MediaColumns.IS_TRASHED + "=0",
                arguments,
                null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) present.add(cursor.getLong(0));
        }
        String versionAfter = MediaStore.getVersion(context, identityVolume);
        long generationAfter = MediaStore.getGeneration(context, identityVolume);
        if (!versionBefore.equals(versionAfter) || generationBefore != generationAfter) {
            return null;
        }
        return present;
    }

    private static ParsedSource parse(String value) {
        if (value == null) return null;
        Uri uri = Uri.parse(value);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !"media".equals(uri.getAuthority())) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 4
                || !VOLUME_NAME.matcher(segments.get(0)).matches()
                || !("images".equals(segments.get(1)) || "video".equals(segments.get(1)))
                || !"media".equals(segments.get(2))) {
            return null;
        }
        try {
            long mediaId = Long.parseLong(segments.get(3));
            return mediaId > 0L
                    ? new ParsedSource(segments.get(0), segments.get(1), mediaId)
                    : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static final class Result {
        final long nextJobId;
        final boolean more;
        final boolean retry;
        final int deletedCount;

        Result(long nextJobId, boolean more, boolean retry, int deletedCount) {
            this.nextJobId = nextJobId;
            this.more = more;
            this.retry = retry;
            this.deletedCount = deletedCount;
        }
    }

    private static final class ParsedSource {
        final String volumeName;
        final String collection;
        final long mediaId;

        ParsedSource(String volumeName, String collection, long mediaId) {
            this.volumeName = volumeName;
            this.collection = collection;
            this.mediaId = mediaId;
        }
    }
}
