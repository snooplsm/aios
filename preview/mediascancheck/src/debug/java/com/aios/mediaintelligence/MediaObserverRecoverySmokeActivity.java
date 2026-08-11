package com.aios.mediaintelligence;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

/** Drives a real MediaStore observer restart scenario on an emulator. */
public final class MediaObserverRecoverySmokeActivity extends Activity {
    private static final String TAG = "AiosMediaRecoverySmoke";
    private static final String EXTRA_ACTION = "action";
    private static final String ACTION_BASELINE = "baseline";
    private static final String ACTION_START_OBSERVER = "start_observer";
    private static final String ACTION_ASSERT = "assert";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String action = getIntent().getStringExtra(EXTRA_ACTION);
            if (ACTION_BASELINE.equals(action)) {
                establishBaseline();
                Log.i(TAG, "AIOS_MEDIA_RECOVERY_BASELINE_OK");
            } else if (ACTION_START_OBSERVER.equals(action)) {
                ComponentName started = startService(
                        new Intent(this, MediaObserverService.class));
                require(started != null, "observer service did not start");
                Log.i(TAG, "AIOS_MEDIA_RECOVERY_OBSERVER_STARTED");
            } else if (ACTION_ASSERT.equals(action)) {
                assertRecoveredBurst();
                Log.i(TAG, "AIOS_MEDIA_RECOVERY_ASSERT_OK");
            } else {
                throw new IllegalArgumentException("unknown recovery smoke action");
            }
        } catch (Throwable error) {
            Log.e(TAG, "AIOS_MEDIA_RECOVERY_FAILED", error);
        } finally {
            finish();
        }
    }

    private void establishBaseline() {
        stopService(new Intent(this, MediaObserverService.class));
        try (MediaJobStore store = new MediaJobStore(this)) {
            MediaGenerationScanner.establishBaselines(this, store);
            require(store.scanState(MediaStore.VOLUME_EXTERNAL_PRIMARY) != null,
                    "primary MediaStore baseline is missing");
        }
    }

    private void assertRecoveredBurst() {
        String historicalName = requiredExtra("historical_name");
        String firstName = requiredExtra("first_name");
        String secondName = requiredExtra("second_name");
        Uri historical = findImage(historicalName);
        Uri first = findImage(firstName);
        Uri second = findImage(secondName);
        try (MediaJobStore store = new MediaJobStore(this)) {
            require(jobCount(store, historical) == 0,
                    "historical baseline image was imported");
            require(workClass(store, first) == MediaWorkPolicy.CLASS_DEFERRED,
                    "first restart burst frame was not deferred");
            require(workClass(store, second) == MediaWorkPolicy.CLASS_DEFERRED,
                    "second restart burst frame was not deferred");
        }
    }

    private String requiredExtra(String name) {
        String value = getIntent().getStringExtra(name);
        require(value != null && !value.isBlank(), "missing " + name);
        return value;
    }

    private Uri findImage(String displayName) {
        Uri collection = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor cursor = getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{displayName},
                null)) {
            require(cursor != null && cursor.moveToFirst(),
                    "fixture is missing from MediaStore: " + displayName);
            long id = cursor.getLong(0);
            require(!cursor.moveToNext(),
                    "fixture display name is not unique: " + displayName);
            return ContentUris.withAppendedId(collection, id);
        }
    }

    private static int jobCount(MediaJobStore store, Uri uri) {
        try (Cursor cursor = store.getReadableDatabase().query(
                "jobs",
                new String[]{"COUNT(*)"},
                "media_uri=?",
                new String[]{uri.toString()},
                null,
                null,
                null)) {
            require(cursor.moveToFirst(), "job count query returned no row");
            return cursor.getInt(0);
        }
    }

    private static int workClass(MediaJobStore store, Uri uri) {
        try (Cursor cursor = store.getReadableDatabase().query(
                "jobs",
                new String[]{"work_class"},
                "media_uri=?",
                new String[]{uri.toString()},
                null,
                null,
                null)) {
            require(cursor.moveToFirst(), "recovered media job is missing: " + uri);
            int workClass = cursor.getInt(0);
            require(!cursor.moveToNext(), "recovered media job is duplicated: " + uri);
            return workClass;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
