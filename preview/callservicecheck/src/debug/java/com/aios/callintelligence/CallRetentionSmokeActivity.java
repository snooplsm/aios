package com.aios.callintelligence;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exercises production call-artifact storage and retention on an emulator. */
public final class CallRetentionSmokeActivity extends Activity {
    private static final String TAG = "AiosCallRetentionSmoke";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        File calls = new File(getFilesDir(), "calls");
        try {
            require(CallArtifactRetention.deleteTree(calls),
                    "could not reset the compile-check call store");
            verifyRetention(calls);
            Log.i(TAG, "AIOS_CALL_RETENTION_SMOKE_OK");
        } catch (Throwable error) {
            Log.e(TAG, "AIOS_CALL_RETENTION_SMOKE_FAILED", error);
        } finally {
            CallArtifactRetention.deleteTree(calls);
            finish();
        }
    }

    private void verifyRetention(File calls) throws Exception {
        CallArtifactStore store = new CallArtifactStore(this);
        String token = UUID.randomUUID().toString();
        String expiredCallId = "expired-" + token;
        String freshCallId = "fresh-" + token;
        long nowEpochMillis = System.currentTimeMillis();

        CallArtifactStore.Session expired = store.create(
                expiredCallId,
                true,
                nowEpochMillis - CallArtifactRetention.RETENTION_MILLIS - 1L);
        require(expired.expiresAtEpochMillis - expired.createdAtEpochMillis
                        == CallArtifactRetention.RETENTION_MILLIS,
                "expired artifact did not receive an exact 24-hour deadline");
        expired.openDownlink().write(new byte[]{1, 2, 3, 4});
        expired.openUplink().write(new byte[]{5, 6, 7, 8});
        expired.appendTranscript("downlink", "en", "Need a repair", true,
                0.9f, 0L, 900L);
        expired.appendAssessment(8, "likely_legitimate", "service_request",
                "model", 1L, nowEpochMillis);
        expired.appendAssistantReply("en", "What address should I use?", nowEpochMillis);
        expired.appendAssistantState(true, 1L, nowEpochMillis);

        CallArtifactStore.Session fresh = store.create(freshCallId, false, nowEpochMillis);
        fresh.appendTranscript("downlink", "es", "Necesito una cita", true,
                0.92f, 0L, 1_100L);
        long originalCreatedAt = fresh.createdAtEpochMillis;
        long originalExpiry = fresh.expiresAtEpochMillis;
        long originalElapsedExpiry = fresh.expiresAtElapsedRealtimeMillis;
        fresh.close();

        CallArtifactStore.Session resumed = store.create(
                freshCallId, true, nowEpochMillis + 60_000L);
        require(resumed.createdAtEpochMillis == originalCreatedAt
                        && resumed.expiresAtEpochMillis == originalExpiry
                        && resumed.expiresAtElapsedRealtimeMillis == originalElapsedExpiry,
                "resuming an artifact extended its retention window");
        File freshDirectory = new File(calls, resumed.sourceId);
        JSONObject freshMetadata = new JSONObject(new String(
                new AtomicFile(new File(freshDirectory, "session.json")).readFully(),
                StandardCharsets.UTF_8));
        require(freshMetadata.getBoolean("answered_by_ai"),
                "resumed artifact did not preserve AI handling state");

        File unreadable = new File(calls, "unreadable-" + token);
        require(unreadable.mkdirs(), "could not create unreadable fixture");
        try (FileOutputStream stream = new FileOutputStream(
                new File(unreadable, "session.json"))) {
            stream.write("not-json".getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }

        long alarmBeforeCleanup = store.nextExpiryElapsedRealtimeMillis();
        require(alarmBeforeCleanup <= SystemClock.elapsedRealtime() + 2_000L,
                "already-expired work was not scheduled for immediate cleanup");
        RetentionAlarm.scheduleNext(this, store);

        store.cleanup(nowEpochMillis);
        File expiredDirectory = new File(calls, expired.sourceId);
        require(!expiredDirectory.exists(), "expired call artifact survived cleanup");
        require(!unreadable.exists(), "unreadable call artifact survived cleanup");
        require(freshDirectory.isDirectory(), "fresh call artifact was deleted early");
        require(store.nextExpiryElapsedRealtimeMillis() == originalElapsedExpiry,
                "nearest alarm does not match the fresh monotonic deadline");

        boolean writerClosed = false;
        try {
            expired.openDownlink();
        } catch (IOException expected) {
            writerClosed = true;
        }
        require(writerClosed, "cleanup left the expired writer usable");

        store.discard(freshCallId);
        require(!freshDirectory.exists(), "explicit artifact deletion failed");
        require(store.nextExpiryElapsedRealtimeMillis() == Long.MAX_VALUE,
                "empty call store retained an expiry alarm");
        RetentionAlarm.scheduleNext(this, store);
        require(empty(calls), "call smoke left private artifacts behind");
    }

    private static boolean empty(File directory) {
        File[] children = directory.listFiles();
        return children == null || children.length == 0;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
