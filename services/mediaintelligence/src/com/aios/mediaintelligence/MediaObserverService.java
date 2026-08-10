package com.aios.mediaintelligence;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
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
import java.util.HashSet;
import java.util.Set;

/** Watches completed media inserts without coupling AIOS to one camera app. */
public final class MediaObserverService extends Service {
    private static final String TAG = "AiosMediaObserver";
    private static final long RECONCILE_RETRY_MILLIS = 30_000L;

    private HandlerThread thread;
    private Handler handler;
    private MediaJobStore store;
    private final Set<String> observedRoots = new HashSet<>();
    private final Runnable reconcileRunnable = this::reconcileAndSchedule;
    private final Runnable livenessRunnable = this::reconcileLiveness;
    private volatile boolean shuttingDown;
    private long livenessCursor;
    private boolean fullLivenessSweep;

    private final ContentObserver observer = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!shuttingDown && !selfChange && uri != null && handler != null) {
                handler.post(() -> {
                    reconcileExactSource(uri);
                    // Collection notifications, pending-row deletion, and
                    // provider-specific URI shapes are recovered by the scan.
                    requestReconcile(CaptureCoalescer.QUIET_PERIOD_MILLIS);
                    requestLivenessBatch(CaptureCoalescer.QUIET_PERIOD_MILLIS);
                });
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
        handler.post(this::initializeObservation);
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
        shuttingDown = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        synchronized (observedRoots) {
            if (!observedRoots.isEmpty()) {
                getContentResolver().unregisterContentObserver(observer);
                observedRoots.clear();
            }
        }
        if (store != null) {
            store.close();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    private void reconcileExactSource(Uri notificationUri) {
        if (shuttingDown) return;
        try {
            if (MediaLivenessScanner.reconcileExact(this, store, notificationUri)) {
                fullLivenessSweep = true;
                livenessCursor = 0L;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot reconcile exact MediaStore source", error);
        }
    }

    private void initializeObservation() {
        if (shuttingDown) return;
        // Scan before registration and once again after it. This closes the
        // startup window without treating the pre-install library as new work.
        scheduleScanResult(MediaGenerationScanner.reconcile(this, store));
        registerObservedVolumes();
        requestReconcile(0L);
        startFullLivenessSweep();
    }

    private void reconcileAndSchedule() {
        if (shuttingDown) return;
        registerObservedVolumes();
        MediaGenerationScanner.ScanResult result =
                MediaGenerationScanner.reconcile(this, store);
        scheduleScanResult(result);
        if (result.more) {
            requestReconcile(0L);
        } else if (result.retry) {
            requestReconcile(RECONCILE_RETRY_MILLIS);
        }
    }

    private void scheduleScanResult(MediaGenerationScanner.ScanResult result) {
        scheduleClasses(result.immediate, result.deferred);
    }

    private void scheduleClasses(boolean immediate, boolean deferred) {
        if (shuttingDown) return;
        if (immediate) {
            MediaInferenceJobService.schedule(this, MediaWorkPolicy.CLASS_IMMEDIATE);
        }
        if (deferred) {
            MediaInferenceJobService.schedule(this, MediaWorkPolicy.CLASS_DEFERRED);
        }
    }

    private void requestReconcile(long delayMillis) {
        if (handler == null || shuttingDown) return;
        handler.removeCallbacks(reconcileRunnable);
        if (delayMillis <= 0L) {
            handler.post(reconcileRunnable);
        } else {
            handler.postDelayed(reconcileRunnable, delayMillis);
        }
    }

    private void startFullLivenessSweep() {
        if (handler == null || shuttingDown) return;
        fullLivenessSweep = true;
        livenessCursor = 0L;
        requestLivenessBatch(0L);
    }

    private void requestLivenessBatch(long delayMillis) {
        if (handler == null || shuttingDown) return;
        handler.removeCallbacks(livenessRunnable);
        if (delayMillis <= 0L) {
            handler.post(livenessRunnable);
        } else {
            handler.postDelayed(livenessRunnable, delayMillis);
        }
    }

    private void reconcileLiveness() {
        if (shuttingDown) return;
        try {
            MediaLivenessScanner.Result result =
                    MediaLivenessScanner.reconcile(this, store, livenessCursor);
            livenessCursor = result.nextJobId;
            if (result.retry) {
                fullLivenessSweep = true;
                livenessCursor = 0L;
                requestLivenessBatch(RECONCILE_RETRY_MILLIS);
            } else if (fullLivenessSweep && result.more) {
                requestLivenessBatch(0L);
            } else {
                fullLivenessSweep = false;
                if (!result.more) livenessCursor = 0L;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot reconcile media source liveness", error);
            fullLivenessSweep = true;
            livenessCursor = 0L;
            requestLivenessBatch(RECONCILE_RETRY_MILLIS);
        }
    }

    private void registerObservedVolumes() {
        if (shuttingDown) return;
        // Keep the synthetic aggregate roots for newly mounted public volumes,
        // then register each current concrete volume for provider compatibility.
        registerObserverRoot(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        registerObserverRoot(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        for (String volumeName : MediaGenerationScanner.externalVolumes(this)) {
            registerObserverRoot(MediaStore.Images.Media.getContentUri(volumeName));
            registerObserverRoot(MediaStore.Video.Media.getContentUri(volumeName));
        }
    }

    private void registerObserverRoot(Uri root) {
        if (shuttingDown) return;
        String key = root.toString();
        synchronized (observedRoots) {
            if (shuttingDown || !observedRoots.add(key)) return;
            try {
                getContentResolver().registerContentObserver(root, true, observer);
            } catch (RuntimeException error) {
                observedRoots.remove(key);
                Log.w(TAG, "cannot register MediaStore observer", error);
            }
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
