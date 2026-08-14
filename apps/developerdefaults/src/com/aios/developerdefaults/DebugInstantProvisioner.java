package com.aios.developerdefaults;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

/** Applies local, debug-only first-boot defaults without logging credentials. */
final class DebugInstantProvisioner {
    private static final String TAG = "AiosDebugProvision";
    private static final String SETUP_WIZARD_PACKAGE = "app.grapheneos.setupwizard";
    private static final String NETWORK_LOCATION_SETTING = "network_location";
    private static final String GEOCODER_SETTING = "geocoder";
    private static final String WIFI_SCAN_ALWAYS_SETTING = "wifi_scan_always_enabled";
    private static final int PRIVACY_PROVIDER = 2;

    private DebugInstantProvisioner() {}

    static void apply(Context context, boolean developerDefaultsAllowed) {
        boolean resourceEnabled = context.getResources().getBoolean(
                R.bool.debug_instant_provisioning);
        String ssid = context.getString(R.string.debug_wifi_ssid);
        String psk = context.getString(R.string.debug_wifi_psk);
        if (!DebugProvisioningPolicy.shouldApply(
                developerDefaultsAllowed, resourceEnabled, ssid, psk)) {
            return;
        }

        boolean provisioned = Settings.Global.putInt(
                context.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        boolean userReady = Settings.Secure.putInt(
                context.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        boolean networkLocation = Settings.Global.putInt(
                context.getContentResolver(), NETWORK_LOCATION_SETTING, PRIVACY_PROVIDER);
        boolean geocoder = Settings.Global.putInt(
                context.getContentResolver(), GEOCODER_SETTING, PRIVACY_PROVIDER);
        boolean wifiScanning = Settings.Global.putInt(
                context.getContentResolver(), WIFI_SCAN_ALWAYS_SETTING, 1);

        LocationManager locationManager = context.getSystemService(LocationManager.class);
        if (locationManager != null) {
            locationManager.setLocationEnabledForUser(true, Process.myUserHandle());
        }

        disableSetupWizard(context);
        boolean wifiSeeded = seedWifi(context, ssid, psk);
        Log.i(TAG, "Applied local debug provisioning: settings="
                + (provisioned && userReady && networkLocation && geocoder && wifiScanning)
                + ", location=" + (locationManager != null)
                + ", wifi=" + wifiSeeded);
    }

    private static void disableSetupWizard(Context context) {
        try {
            context.getPackageManager().setApplicationEnabledSetting(
                    SETUP_WIZARD_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (IllegalArgumentException exception) {
            Log.i(TAG, "No device setup-wizard package to disable");
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean seedWifi(Context context, String ssid, String psk) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi == null || !wifi.setWifiEnabled(true)) {
            return false;
        }
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = quoteWifiValue(ssid);
        configuration.preSharedKey = quoteWifiValue(psk);
        configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        WifiManager.AddNetworkResult result = wifi.addNetworkPrivileged(configuration);
        return result.statusCode == WifiManager.AddNetworkResult.STATUS_SUCCESS
                && wifi.enableNetwork(result.networkId, true)
                && wifi.reconnect();
    }

    private static String quoteWifiValue(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
