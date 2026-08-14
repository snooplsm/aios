package com.aios.developerdefaults;

/** Pure release boundary for development-only device defaults. */
final class DeveloperDefaultsPolicy {
    private DeveloperDefaultsPolicy() {}

    static boolean shouldApply(boolean debuggable, boolean productFlagEnabled) {
        return debuggable && productFlagEnabled;
    }
}
