package com.aios.modelbroker;

/** Handles Android's non-monotonic ComponentCallbacks2 trim-level families. */
final class MemoryTrimPolicy {
    private static final int RUNNING_LOW = 10;
    private static final int RUNNING_CRITICAL = 15;
    private static final int BACKGROUND = 40;

    private MemoryTrimPolicy() {}

    static boolean shouldPreemptBackground(int level) {
        return level == RUNNING_LOW || level == RUNNING_CRITICAL || level >= BACKGROUND;
    }
}
