package com.aios.developerdefaults;

/** Pure release boundary for development-only device defaults. */
final class DeveloperDefaultsPolicy {
    private DeveloperDefaultsPolicy() {}

    static boolean shouldApply(String buildType, boolean productFlagEnabled) {
        return ("eng".equals(buildType) || "userdebug".equals(buildType))
                && productFlagEnabled;
    }
}
