package com.aios.contextintelligence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Removes expired call-derived retrieval entries after credential-unlocked boot. */
public final class ContextBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try (ContextStore store = new ContextStore(context.getApplicationContext())) {
                store.purgeExpired(System.currentTimeMillis());
            } finally {
                executor.shutdown();
                pending.finish();
            }
        });
    }
}
