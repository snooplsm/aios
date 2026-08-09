package com.aios.callintelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CleanupBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !RetentionAlarm.ACTION_CLEANUP.equals(intent.getAction())) {
            return;
        }
        PendingResult pending = goAsync();
        Thread worker = new Thread(() -> {
            try {
                CallArtifactStore store = new CallArtifactStore(context);
                store.cleanup(System.currentTimeMillis());
                RetentionAlarm.scheduleNext(context, store);
            } finally {
                pending.finish();
            }
        }, "aios-call-retention-boot");
        worker.start();
    }
}
