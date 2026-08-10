package com.aios.mediaintelligence;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Watches completed media inserts without coupling AIOS to one camera app. */
public final class MediaObserverService extends Service {
    private static final String TAG = "AiosMediaObserver";

    private HandlerThread thread;
    private Handler handler;
    private CaptureCoalescer coalescer;
    private MediaJobStore store;

    private final ContentObserver observer = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!selfChange && uri != null && handler != null) {
                handler.post(() -> observeSettledItem(uri));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new MediaJobStore(this);
        thread = new HandlerThread("aios-media-observer");
        thread.start();
        handler = new Handler(thread.getLooper());
        coalescer = new CaptureCoalescer(handler, this::onCaptureGroupSettled);
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        getContentResolver().registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        if (coalescer != null) {
            coalescer.close();
        }
        if (store != null) {
            store.close();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    private void observeSettledItem(Uri uri) {
        String[] projection = {
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE
        };
        try (Cursor cursor = getContentResolver().query(
                uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            long generation = cursor.getLong(0);
            int pending = cursor.getInt(1);
            String mimeType = cursor.getString(2);
            long size = cursor.getLong(3);
            if (pending != 0 || size <= 0L || mimeType == null) {
                return;
            }
            if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
                return;
            }
            if (store.shouldSuppressOwnMutation(uri.toString(), generation)) {
                return;
            }
            coalescer.add(new CaptureCoalescer.ObservedMedia(
                    uri.toString(), generation, mimeType, System.currentTimeMillis()));
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot inspect changed media: " + uri, error);
        }
    }

    private void onCaptureGroupSettled(List<CaptureCoalescer.ObservedMedia> group) {
        int groupSize = group.size();
        for (CaptureCoalescer.ObservedMedia media : group) {
            int workClass = MediaWorkPolicy.schedulingClass(media.mimeType, groupSize);
            store.enqueue(media, workClass);
            MediaInferenceJobService.schedule(this, workClass);
        }
    }

    @Override
    protected void dump(FileDescriptor descriptor, PrintWriter writer, String[] arguments) {
        if ("user".equals(Build.TYPE)) {
            writer.println("AIOS media timing is available only on debuggable builds");
            return;
        }
        boolean timingJson = false;
        for (String argument : arguments) {
            if ("--timing-json".equals(argument)) timingJson = true;
        }
        if (!timingJson) {
            writer.println("Use --timing-json for privacy-minimized aggregate latency data");
            return;
        }
        try (MediaJobStore timingStore = new MediaJobStore(this)) {
            String json = timingStore.timingSummary().toJson();
            writer.println("AIOS_MEDIA_TIMING_BASE64=" + Base64.encodeToString(
                    json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (RuntimeException error) {
            writer.println("AIOS_MEDIA_TIMING_ERROR=unavailable");
            Log.w(TAG, "cannot produce aggregate media timing", error);
        }
    }
}
