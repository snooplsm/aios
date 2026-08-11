package com.aios.callintelligence;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.UUID;

/** Captures the wall and monotonic clocks under one stable boot identity. */
final class RetentionClock {
    private static final String PROCESS_BOOT_IDENTITY = "process:" + UUID.randomUUID();

    static final class Snapshot {
        final String bootIdentity;
        final long epochMillis;
        final long elapsedRealtimeMillis;

        Snapshot(String bootIdentity, long epochMillis, long elapsedRealtimeMillis) {
            this.bootIdentity = bootIdentity;
            this.epochMillis = epochMillis;
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        }
    }

    private RetentionClock() {}

    static Snapshot capture(Context context, long nowEpochMillis) {
        int bootCount = Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        String identity = bootCount >= 0
                ? "android-boot:" + bootCount
                : PROCESS_BOOT_IDENTITY;
        return new Snapshot(identity, nowEpochMillis, SystemClock.elapsedRealtime());
    }
}
