package com.aios.contextintelligence;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Schedules the nearest call-context expiry against elapsed realtime. */
final class ContextRetentionAlarm {
    static final String ACTION_CLEANUP =
            "com.aios.contextintelligence.CLEANUP_EXPIRED_CONTEXT";
    private static final int REQUEST_CODE = 2401;

    private ContextRetentionAlarm() {}

    static void scheduleNext(Context context, ContextStore store) {
        schedule(context, store.nextExpiryElapsedRealtimeMillis());
    }

    static void schedule(Context context, long triggerElapsedRealtimeMillis) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                new Intent(context, ContextBootReceiver.class).setAction(ACTION_CLEANUP),
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
            manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtimeMillis,
                    operation);
        }
    }
}
