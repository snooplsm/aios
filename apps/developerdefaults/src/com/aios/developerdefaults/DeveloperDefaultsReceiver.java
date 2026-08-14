package com.aios.developerdefaults;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

/** Makes developer options and authenticated ADB visible on opted-in debug images. */
public final class DeveloperDefaultsReceiver extends BroadcastReceiver {
    private static final String TAG = "AiosDeveloperDefaults";
    private static final String ENABLED_METADATA = "com.aios.developer_defaults";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Bundle metadata = context.getApplicationInfo().metaData;
        boolean productFlagEnabled = metadata != null
                && metadata.getBoolean(ENABLED_METADATA, false);
        boolean developerDefaultsAllowed = DeveloperDefaultsPolicy.shouldApply(
                Build.TYPE, productFlagEnabled);
        if (!developerDefaultsAllowed) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        boolean development = Settings.Global.putInt(
                context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                1);
        boolean adb = Settings.Global.putInt(
                context.getContentResolver(),
                Settings.Global.ADB_ENABLED,
                1);
        if (!development || !adb) {
            Log.e(TAG, "Could not apply opted-in development defaults");
        }
        DebugInstantProvisioner.apply(context, developerDefaultsAllowed);
    }
}
