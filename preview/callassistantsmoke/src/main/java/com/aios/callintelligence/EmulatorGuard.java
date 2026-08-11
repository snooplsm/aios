package com.aios.callintelligence;

import android.os.Build;

/** Keeps the synthetic call-assistant transport unavailable on physical hardware. */
final class EmulatorGuard {
    private EmulatorGuard() {}

    static boolean isEmulator() {
        return "ranchu".equalsIgnoreCase(Build.HARDWARE)
                || "goldfish".equalsIgnoreCase(Build.HARDWARE)
                || Build.PRODUCT.toLowerCase(java.util.Locale.ROOT).contains("sdk")
                || Build.FINGERPRINT.startsWith("generic");
    }
}
