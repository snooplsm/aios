package com.aios.developerdefaults;

/** Pure release boundary for local, credential-bearing debug provisioning. */
final class DebugProvisioningPolicy {
    private DebugProvisioningPolicy() {}

    static boolean shouldApply(
            boolean developerDefaultsAllowed,
            boolean resourceEnabled,
            String ssid,
            String psk) {
        return developerDefaultsAllowed
                && resourceEnabled
                && ssid != null
                && !ssid.isBlank()
                && psk != null
                && psk.length() >= 8;
    }
}
