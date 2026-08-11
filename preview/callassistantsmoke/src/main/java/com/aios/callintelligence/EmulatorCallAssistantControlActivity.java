package com.aios.callintelligence;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.File;

/** Shell-only control surface for the emulator call-assistant fixture. */
public final class EmulatorCallAssistantControlActivity extends Activity {
    static final String ACTION_CONFIGURE = "com.aios.callintelligence.smoke.CONFIGURE";
    static final String ACTION_RESET_AUDIT = "com.aios.callintelligence.smoke.RESET_AUDIT";
    static final String EXTRA_AVAILABLE = "available";
    static final String EXTRA_ANSWER_MODE = "answer_mode";
    static final String EXTRA_ANSWER_DELAY_MODE = "answer_delay_mode";
    static final String EXTRA_PROCESSING_ENABLED = "processing_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!EmulatorGuard.isEmulator()) {
            throw new IllegalStateException("The call-assistant fixture only runs on an emulator");
        }
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!EmulatorGuard.isEmulator()) {
            throw new IllegalStateException("The call-assistant fixture only runs on an emulator");
        }
        handle(intent);
    }

    private void handle(Intent command) {
        if (ACTION_CONFIGURE.equals(command.getAction())) {
            String answerMode = command.getStringExtra(EXTRA_ANSWER_MODE);
            String delayMode = command.getStringExtra(EXTRA_ANSWER_DELAY_MODE);
            if (!CallPolicyEngine.isKnownMode(answerMode)
                    || !AnswerDelayPolicy.isKnownMode(delayMode)) {
                throw new IllegalArgumentException("known answer and delay modes are required");
            }
            SharedPreferences.Editor editor = getSharedPreferences(
                    EmulatorCallAssistantService.PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(
                            EmulatorCallAssistantService.KEY_AVAILABLE,
                            command.getBooleanExtra(EXTRA_AVAILABLE, false))
                    .putString(EmulatorCallAssistantService.KEY_ANSWER_MODE, answerMode)
                    .putString(EmulatorCallAssistantService.KEY_DELAY_MODE, delayMode)
                    .putBoolean(
                            EmulatorCallAssistantService.KEY_PROCESSING_ENABLED,
                            command.getBooleanExtra(EXTRA_PROCESSING_ENABLED, false));
            if (!editor.commit()) {
                throw new IllegalStateException("fixture policy could not be saved");
            }
            EmulatorCallAssistantService.notifyAvailabilityChanged();
        } else if (ACTION_RESET_AUDIT.equals(command.getAction())) {
            File audit = new File(getCacheDir(), EmulatorCallAssistantService.AUDIT_FILE);
            if (audit.exists() && !audit.delete()) {
                throw new IllegalStateException("fixture audit could not be reset");
            }
        } else {
            throw new IllegalArgumentException("unsupported fixture action");
        }
        finish();
    }
}
