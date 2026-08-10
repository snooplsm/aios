package com.aios.mediaintelligence;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Recovers the durable two-phase publication journal for enhanced MP4 copies. */
final class VideoExportRecovery {
    static final Object LOCK = new Object();

    private static final String TAG = "AiosVideoRecovery";
    private static final String MARKER_PREFIX = "aios-enhanced-export:";
    private static final int BATCH_SIZE = 128;
    private static final int MAX_BATCHES = 32;
    private static final long SUPPRESSION_MILLIS = 24L * 60L * 60L * 1_000L;

    private VideoExportRecovery() {}

    static String marker(String token) {
        return MARKER_PREFIX + token;
    }

    static Bundle includePending() {
        Bundle arguments = new Bundle();
        arguments.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        return arguments;
    }

    static void recover(Context context, MediaJobStore store) {
        synchronized (LOCK) {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<MediaJobStore.PendingVideoExport> pending =
                        store.pendingVideoExports(BATCH_SIZE);
                if (pending.isEmpty()) return;
                int completed = 0;
                for (MediaJobStore.PendingVideoExport export : pending) {
                    try {
                        recoverOneLocked(context, store, export);
                        completed++;
                    } catch (IOException | RuntimeException error) {
                        Log.w(TAG, "cannot recover enhanced-video export", error);
                    }
                }
                if (pending.size() < BATCH_SIZE || completed == 0) return;
            }
            Log.w(TAG, "enhanced-video recovery stopped at its batch bound");
        }
    }

    static boolean recoverToken(Context context, MediaJobStore store, String token) {
        synchronized (LOCK) {
            MediaJobStore.PendingVideoExport export = store.pendingVideoExport(token);
            if (export == null) return false;
            try {
                return recoverOneLocked(context, store, export)
                        == VideoExportRecoveryPolicy.PRESERVE_PUBLISHED;
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "cannot clean failed enhanced-video export", error);
                return false;
            }
        }
    }

    private static int recoverOneLocked(
            Context context,
            MediaJobStore store,
            MediaJobStore.PendingVideoExport export) throws IOException {
        if (export.outputUri.isBlank()) {
            deleteUnattachedOutput(context, export);
            store.clearFailedVideoExport(export.token, "");
            return VideoExportRecoveryPolicy.FORGET_ABSENT;
        }

        Uri output = Uri.parse(export.outputUri);
        if (!VideoEnhancedCopyService.isCanonicalVideoUri(output)) {
            store.clearFailedVideoExport(export.token, export.outputUri);
            Log.w(TAG, "forgot an invalid enhanced-video journal URI");
            return VideoExportRecoveryPolicy.FORGET_UNTRUSTED;
        }
        OutputObservation observation = observe(context, output, export.token);
        int decision = VideoExportRecoveryPolicy.decide(
                observation.exists,
                observation.pending,
                observation.ownerMatches,
                observation.mp4MimeMatches,
                observation.pendingMarkerMatches);
        switch (decision) {
            case VideoExportRecoveryPolicy.FORGET_ABSENT ->
                    store.clearFailedVideoExport(export.token, export.outputUri);
            case VideoExportRecoveryPolicy.DELETE_PENDING -> {
                int deleted = context.getContentResolver().delete(output, includePending());
                if (deleted != 1) throw new IOException("pending enhanced MP4 was not deleted");
                store.clearFailedVideoExport(export.token, export.outputUri);
            }
            case VideoExportRecoveryPolicy.PRESERVE_PUBLISHED -> {
                long expires = Math.addExact(System.currentTimeMillis(), SUPPRESSION_MILLIS);
                store.finishOwnMutation(export.outputUri, observation.generation, expires);
                store.clearVideoExportJournal(export.token);
            }
            case VideoExportRecoveryPolicy.FORGET_UNTRUSTED -> {
                store.clearFailedVideoExport(export.token, export.outputUri);
                Log.w(TAG, "refused to delete an untrusted enhanced-video journal target");
            }
            default -> throw new AssertionError("unknown enhanced-video recovery decision");
        }
        return decision;
    }

    private static void deleteUnattachedOutput(
            Context context, MediaJobStore.PendingVideoExport export) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(export.outputVolume);
        List<Uri> matches = new ArrayList<>();
        Bundle arguments = includePending();
        arguments.putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
                        + MediaStore.MediaColumns.IS_PENDING + "=1 AND "
                        + MediaStore.Video.VideoColumns.DESCRIPTION + "=?");
        arguments.putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{context.getPackageName(), marker(export.token)});
        try (Cursor cursor = resolver.query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                arguments,
                null)) {
            if (cursor == null) throw new IOException("pending export query is unavailable");
            while (cursor.moveToNext()) {
                matches.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
            }
        } catch (RuntimeException error) {
            throw new IOException("cannot find unattached enhanced MP4", error);
        }
        for (Uri output : matches) {
            if (resolver.delete(output, includePending()) != 1) {
                throw new IOException("unattached pending MP4 was not deleted");
            }
        }
    }

    private static OutputObservation observe(Context context, Uri output, String token)
            throws IOException {
        try (Cursor cursor = context.getContentResolver().query(
                output,
                new String[]{
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.Video.VideoColumns.DESCRIPTION,
                        MediaStore.MediaColumns.GENERATION_MODIFIED
                },
                includePending(),
                null)) {
            if (cursor == null) throw new IOException("enhanced-video output query unavailable");
            if (!cursor.moveToFirst()) return OutputObservation.ABSENT;
            boolean pending = cursor.getInt(0) != 0;
            String owner = cursor.getString(1);
            String mime = cursor.getString(2);
            String description = cursor.getString(3);
            return new OutputObservation(
                    true,
                    pending,
                    context.getPackageName().equals(owner),
                    "video/mp4".equalsIgnoreCase(mime),
                    marker(token).equals(description),
                    cursor.getLong(4));
        } catch (RuntimeException error) {
            throw new IOException("cannot inspect journaled enhanced MP4", error);
        }
    }

    private static final class OutputObservation {
        static final OutputObservation ABSENT = new OutputObservation(
                false, false, false, false, false, -1L);

        final boolean exists;
        final boolean pending;
        final boolean ownerMatches;
        final boolean mp4MimeMatches;
        final boolean pendingMarkerMatches;
        final long generation;

        OutputObservation(
                boolean exists,
                boolean pending,
                boolean ownerMatches,
                boolean mp4MimeMatches,
                boolean pendingMarkerMatches,
                long generation) {
            this.exists = exists;
            this.pending = pending;
            this.ownerMatches = ownerMatches;
            this.mp4MimeMatches = mp4MimeMatches;
            this.pendingMarkerMatches = pendingMarkerMatches;
            this.generation = generation;
        }
    }
}
