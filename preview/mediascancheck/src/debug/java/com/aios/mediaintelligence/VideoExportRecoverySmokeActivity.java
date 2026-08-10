package com.aios.mediaintelligence;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Emulator-only exercise of attached, unattached, and published journal recovery. */
public final class VideoExportRecoverySmokeActivity extends Activity {
    private static final String TAG = "AiosVideoRecoverySmoke";
    private static final String SOURCE = "content://media/external/video/media/1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<Uri> cleanup = new ArrayList<>();
        try (MediaJobStore store = new MediaJobStore(this)) {
            Uri fixture = getIntent().getData();
            require(fixture != null, "valid MP4 fixture URI is required");
            Uri attached = createJournaledOutput(store, true, cleanup);
            VideoExportRecovery.recover(this, store);
            require(!exists(attached), "attached pending output survived recovery");
            cleanup.remove(attached);

            Uri unattached = createJournaledOutput(store, false, cleanup);
            VideoExportRecovery.recover(this, store);
            require(!exists(unattached), "unattached pending output survived recovery");
            cleanup.remove(unattached);

            String publishedToken = UUID.randomUUID().toString();
            store.beginVideoExport(
                    publishedToken,
                    SOURCE,
                    1L,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    System.currentTimeMillis());
            Uri published = insertPending(publishedToken);
            cleanup.add(published);
            store.attachVideoExportOutput(publishedToken, published.toString());
            copyFixture(fixture, published);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            values.putNull(MediaStore.Video.VideoColumns.DESCRIPTION);
            require(getContentResolver().update(
                    published, values, VideoExportRecovery.includePending()) == 1,
                    "published smoke output update failed");
            VideoExportRecovery.recover(this, store);
            require(exists(published), "published output was deleted by recovery");
            require(store.pendingVideoExport(publishedToken) == null,
                    "published journal survived recovery");

            Log.i(TAG, "AIOS_VIDEO_RECOVERY_SMOKE_OK");
        } catch (Exception error) {
            Log.e(TAG, "AIOS_VIDEO_RECOVERY_SMOKE_FAILED", error);
        } finally {
            for (Uri uri : cleanup) {
                try {
                    getContentResolver().delete(uri, VideoExportRecovery.includePending());
                } catch (RuntimeException error) {
                    Log.w(TAG, "cannot erase recovery smoke output", error);
                }
            }
            try (MediaJobStore store = new MediaJobStore(this)) {
                VideoExportRecovery.recover(this, store);
            }
            eraseSmokeOutputs();
            finishAndRemoveTask();
        }
    }

    private Uri createJournaledOutput(
            MediaJobStore store, boolean attach, List<Uri> cleanup) {
        String token = UUID.randomUUID().toString();
        store.beginVideoExport(
                token,
                SOURCE,
                1L,
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
                System.currentTimeMillis());
        Uri output = insertPending(token);
        cleanup.add(output);
        if (attach) {
            store.attachVideoExportOutput(token, output.toString());
            store.beginOwnMutation(
                    output.toString(), System.currentTimeMillis() + 60_000L);
        }
        return output;
    }

    private Uri insertPending(String token) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                "aios_recovery_smoke_" + token + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/AIOS");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        values.put(MediaStore.Video.VideoColumns.DESCRIPTION,
                VideoExportRecovery.marker(token));
        Uri output = getContentResolver().insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values);
        if (output == null) throw new IllegalStateException("smoke output insert failed");
        return output;
    }

    private boolean exists(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns._ID},
                VideoExportRecovery.includePending(),
                null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    private void copyFixture(Uri fixture, Uri output) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(fixture);
             OutputStream target = getContentResolver().openOutputStream(output, "w")) {
            if (input == null || target == null) {
                throw new IllegalStateException("smoke fixture stream unavailable");
            }
            input.transferTo(target);
            target.flush();
        }
    }

    private void eraseSmokeOutputs() {
        Uri collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Bundle arguments = VideoExportRecovery.includePending();
        arguments.putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?");
        arguments.putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{getPackageName(), "aios_recovery_smoke_%"});
        List<Uri> matches = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                arguments,
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    matches.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
                }
            }
        }
        for (Uri match : matches) {
            getContentResolver().delete(match, VideoExportRecovery.includePending());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
