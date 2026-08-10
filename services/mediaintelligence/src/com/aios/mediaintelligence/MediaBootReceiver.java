package com.aios.mediaintelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;

public final class MediaBootReceiver extends BroadcastReceiver {
    private static final String TAG = "AiosMediaBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            Context application = context.getApplicationContext();
            new Thread(() -> {
                try (MediaJobStore store = new MediaJobStore(application)) {
                    try {
                        VideoStoryboard.eraseCached(application);
                    } catch (IOException error) {
                        Log.e(TAG, "cannot erase recovered video storyboards", error);
                    }
                    VideoExportRecovery.recover(application, store);
                    store.recoverInterruptedWork();
                    new MediaMetadataCommitter(application).recover(store);
                } finally {
                    MediaContextAssociationService.requestReconcile(application);
                    application.startService(
                            new Intent(application, MediaObserverService.class));
                    MediaInferenceJobService.schedule(
                            application, MediaWorkPolicy.CLASS_IMMEDIATE);
                    MediaInferenceJobService.schedule(
                            application, MediaWorkPolicy.CLASS_DEFERRED);
                    pending.finish();
                }
            }, "aios-media-recovery").start();
        }
    }
}
