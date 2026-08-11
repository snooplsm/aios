package com.aios.callintelligence;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Schedules the nearest artifact expiry; boot cleanup repairs missed alarms. */
final class RetentionAlarm {
    static final String ACTION_CLEANUP = "com.aios.callintelligence.CLEANUP_EXPIRED";
    private static final int REQUEST_CODE = 2400;

    private RetentionAlarm() {}

    static void scheduleNext(Context context, CallArtifactStore store) {
        schedule(context, store.nextExpiryElapsedRealtimeMillis());
    }

    static void schedule(Context context, long triggerElapsedRealtimeMillis) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) {
            return;
        }
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                new Intent(context, CleanupBootReceiver.class).setAction(ACTION_CLEANUP),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (triggerElapsedRealtimeMillis == Long.MAX_VALUE) {
            manager.cancel(operation);
            return;
        }
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtimeMillis,
                    operation);
        } else {
            // Policy-restricted builds still clean automatically, with OS batching.
            manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtimeMillis,
                    operation);
        }
    }
}
