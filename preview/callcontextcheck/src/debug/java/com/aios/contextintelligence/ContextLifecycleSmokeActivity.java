package com.aios.contextintelligence;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;
import com.aios.context.ConversationIdentity;
import com.aios.context.ICommunicationContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exercises the production Binder, SQLite/FTS, revision, and retention path on Android. */
public final class ContextLifecycleSmokeActivity extends Activity {
    private static final String TAG = "AiosContextSmoke";
    private static final String DATABASE = "communication_context.db";
    private static final String PREFS = "opaque_identity";

    private boolean bound;
    private Throwable result;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                verify(ICommunicationContext.Stub.asInterface(binder));
            } catch (Throwable error) {
                result = error;
            } finally {
                finishFixture();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // A normal unbind below tears down the in-process fixture service.
        }

        @Override
        public void onNullBinding(ComponentName name) {
            result = new IllegalStateException("production context service returned a null binding");
            finishFixture();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        deleteDatabase(DATABASE);
        deleteSharedPreferences(PREFS);
        Intent intent = new Intent(this, CommunicationContextService.class)
                .setAction(CommunicationContextService.ACTION);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            result = new IllegalStateException("could not bind production context service");
            finishFixture();
        }
    }

    private void verify(ICommunicationContext remote) throws Exception {
        require(remote != null, "AIDL interface was unavailable");
        String instance = remote.getStoreInstanceId();
        require(instance.matches("[0-9a-f]{32}"), "store instance ID is not opaque");
        require(instance.equals(remote.getStoreInstanceId()), "store instance ID changed");

        ConversationIdentity identity = remote.resolveIdentity("(212) 555-0100", "US");
        ConversationIdentity same = remote.resolveIdentity("+1 212-555-0100", "US");
        ConversationIdentity other = remote.resolveIdentity("+1 212-555-0101", "US");
        require(identity.conversationKey.matches("number:[0-9a-f]{64}"),
                "conversation identity is not an opaque HMAC");
        require(identity.conversationKey.equals(same.conversationKey),
                "equivalent US numbers did not converge");
        require(!identity.conversationKey.equals(other.conversationKey),
                "different numbers shared an identity");
        require(identity.relatedConversationKeys.length >= 1
                        && identity.conversationKey.equals(identity.relatedConversationKeys[0]),
                "primary conversation identity is not queryable");

        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ContextRetentionClock.Snapshot clock = ContextRetentionClock.capture(this, now);
        ContextStore store = new ContextStore(this);
        try {
            store.upsert(document(ContextPolicy.SMS, "sms-" + token, 1L, identity,
                    now - 5_000L, "Customer needs a blue sink repair"));
            store.upsert(document(ContextPolicy.MMS, "mms-" + token, 1L, identity,
                    now - 4_000L, "Photo shows a cracked copper pipe"));
            store.upsert(document(ContextPolicy.CALL_EVENT, "call-" + token, 1L, identity,
                    now - 3_000L, "Missed estimate call from customer"));
            store.upsert(document(ContextPolicy.MEDIA_METADATA, "media-" + token, 1L, identity,
                    now - 2_000L, "Photo contains a leaking water heater label"));

            ContextDocument freshArtifact = expiringArtifact(
                    "artifact-" + token, 1L, identity, now - 1_000L,
                    clock.bootIdentity, Math.max(0L, clock.elapsedRealtimeMillis - 1_000L),
                    "Spanish caller requested una cita tomorrow");
            ContextRetentionClock.Snapshot beforeWrite = ContextRetentionClock.capture(this, now);
            require(!ContextExpiryPolicy.isExpired(
                            freshArtifact.eventAtEpochMillis,
                            freshArtifact.expiresAtEpochMillis,
                            freshArtifact.expiryBootIdentity,
                            freshArtifact.createdAtElapsedRealtimeMillis,
                            freshArtifact.expiresAtElapsedRealtimeMillis,
                            beforeWrite.bootIdentity,
                            beforeWrite.epochMillis,
                            beforeWrite.elapsedRealtimeMillis),
                    "fresh call artifact was expired before its Binder write");
            remote.upsert(freshArtifact);
            require(hasSource(store, ContextPolicy.CALL_ARTIFACT, freshArtifact.sourceId),
                    "Binder call-artifact write was missing immediately after return");

            String[] allSources = {
                    ContextPolicy.SMS,
                    ContextPolicy.MMS,
                    ContextPolicy.CALL_EVENT,
                    ContextPolicy.CALL_ARTIFACT,
                    ContextPolicy.MEDIA_METADATA
            };
            List<ContextSnippet> all = remote.query(identity, allSources, "", 8, now);
            require(all.size() == 5,
                    "cross-source retrieval returned " + all.size() + " documents: "
                            + sourceTypes(all));
            require(sourceTypes(all).equals(Set.of(
                            ContextPolicy.SMS,
                            ContextPolicy.MMS,
                            ContextPolicy.CALL_EVENT,
                            ContextPolicy.CALL_ARTIFACT,
                            ContextPolicy.MEDIA_METADATA)),
                    "cross-source retrieval omitted a communication source");
            require(descending(all), "retrieval was not newest-first");
            require(remote.query(identity, allSources, "", 2, now).size() == 2,
                    "Binder query limit was not enforced");
            List<ContextSnippet> copper = remote.query(
                    identity, new String[]{ContextPolicy.MMS}, "copper pipe", 8, now);
            require(copper.size() == 1 && copper.get(0).sourceId.equals("mms-" + token),
                    "Android FTS4 did not intersect the MMS query tokens");
            require(remote.query(identity, new String[]{ContextPolicy.MEDIA_METADATA},
                            "leaking", 8, now).size() == 1,
                    "source-scoped photo metadata retrieval failed");
            verifyOpaqueDatabase(store);

            store.upsert(document(ContextPolicy.SMS, "sms-" + token, 2L, identity,
                    now + 1L, "Customer confirmed a green vanity installation"));
            require(remote.query(identity, new String[]{ContextPolicy.SMS},
                            "green vanity", 8, now).size() == 1,
                    "newer SMS revision did not replace the old document");
            require(remote.query(identity, new String[]{ContextPolicy.SMS},
                            "blue sink", 8, now).isEmpty(),
                    "replaced SMS text remained in the FTS index");
            store.deleteSource(ContextPolicy.SMS, "sms-" + token, 3L);
            store.upsert(document(ContextPolicy.SMS, "sms-" + token, 2L, identity,
                    now + 2L, "Stale SMS must not return"));
            require(remote.query(identity, new String[]{ContextPolicy.SMS},
                            "", 8, now).isEmpty(),
                    "SMS tombstone allowed a stale replay");

            long mediaWatermark = store.deleteSourceType(ContextPolicy.MEDIA_METADATA, 5L);
            require(mediaWatermark == 5L, "media bulk-delete watermark was incorrect");
            store.upsert(document(ContextPolicy.MEDIA_METADATA, "media-" + token, 4L, identity,
                    now + 3L, "Stale photo metadata must not return"));
            require(remote.query(identity, new String[]{ContextPolicy.MEDIA_METADATA},
                            "", 8, now).isEmpty(),
                    "media watermark allowed a stale replay");
            store.upsert(document(ContextPolicy.MEDIA_METADATA, "media-" + token, 6L, identity,
                    now + 4L, "Fresh photo metadata returns after reconciliation"));
            require(remote.query(identity, new String[]{ContextPolicy.MEDIA_METADATA},
                            "fresh photo", 8, now).size() == 1,
                    "new media revision did not advance past the watermark");

            remote.deleteSource(ContextPolicy.CALL_ARTIFACT, freshArtifact.sourceId, 2L);
            remote.upsert(freshArtifact);
            require(remote.query(identity, new String[]{ContextPolicy.CALL_ARTIFACT},
                            "", 8, now).isEmpty(),
                    "Binder tombstone allowed a stale call artifact replay");

            long expiredEvent = now - ContextPolicy.CALL_ARTIFACT_TTL_MILLIS - 1L;
            ContextDocument expiredArtifact = expiringArtifact(
                    "expired-" + token, 1L, identity, expiredEvent,
                    clock.bootIdentity, 0L, "Expired transcript must be purged");
            remote.upsert(expiredArtifact);
            require(remote.query(identity, new String[]{ContextPolicy.CALL_ARTIFACT},
                            "", 8, now).isEmpty(),
                    "wall-clock-expired call context survived the Android purge");

            store.deleteSourceType(ContextPolicy.MMS, 10L);
            store.deleteSourceType(ContextPolicy.CALL_EVENT, 10L);
            store.deleteSourceType(ContextPolicy.MEDIA_METADATA, 10L);
            remote.purgeExpired(now);
            require(remote.query(identity, allSources, "", 8, now).isEmpty(),
                    "context fixture left retrievable documents behind");
            require(store.nextExpiryElapsedRealtimeMillis() == Long.MAX_VALUE,
                    "empty context store retained an expiry deadline");
        } finally {
            store.close();
        }
    }

    private static ContextDocument document(
            String sourceType,
            String sourceId,
            long revision,
            ConversationIdentity identity,
            long eventAtEpochMillis,
            String text) {
        return new ContextDocument(
                sourceType, sourceId, revision, identity, eventAtEpochMillis, 0L, text);
    }

    private static ContextDocument expiringArtifact(
            String sourceId,
            long revision,
            ConversationIdentity identity,
            long eventAtEpochMillis,
            String bootIdentity,
            long createdAtElapsedRealtimeMillis,
            String text) {
        return new ContextDocument(
                ContextPolicy.CALL_ARTIFACT,
                sourceId,
                revision,
                identity,
                eventAtEpochMillis,
                Math.addExact(eventAtEpochMillis, ContextPolicy.CALL_ARTIFACT_TTL_MILLIS),
                bootIdentity,
                createdAtElapsedRealtimeMillis,
                Math.addExact(createdAtElapsedRealtimeMillis,
                        ContextPolicy.CALL_ARTIFACT_TTL_MILLIS),
                text);
    }

    private static Set<String> sourceTypes(List<ContextSnippet> snippets) {
        Set<String> result = new HashSet<>();
        for (ContextSnippet snippet : snippets) result.add(snippet.sourceType);
        return result;
    }

    private static boolean descending(List<ContextSnippet> snippets) {
        for (int index = 1; index < snippets.size(); index++) {
            if (snippets.get(index - 1).eventAtEpochMillis
                    < snippets.get(index).eventAtEpochMillis) return false;
        }
        return true;
    }

    private static void verifyOpaqueDatabase(ContextStore store) {
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT conversation_key,contact_key,source_id,body FROM entries", null)) {
            while (cursor.moveToNext()) {
                String row = cursor.getString(0) + " " + cursor.getString(1) + " "
                        + cursor.getString(2) + " " + cursor.getString(3);
                require(!row.contains("2125550100") && !row.contains("212 555 0100")
                                && !row.contains("(212)"),
                        "raw caller address reached the communication database");
            }
        }
    }

    private static boolean hasSource(ContextStore store, String sourceType, String sourceId) {
        try (Cursor cursor = store.getReadableDatabase().query(
                "entries",
                new String[]{"_id"},
                "source_type=? AND source_id=?",
                new String[]{sourceType, sourceId},
                null,
                null,
                null)) {
            return cursor.moveToFirst();
        }
    }

    private void finishFixture() {
        if (bound) {
            bound = false;
            unbindService(connection);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean databaseDeleted = deleteDatabase(DATABASE);
            boolean preferencesDeleted = deleteSharedPreferences(PREFS);
            if (!databaseDeleted && getDatabasePath(DATABASE).exists()) {
                result = new IllegalStateException("context database survived fixture cleanup");
            }
            if (!preferencesDeleted
                    && getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("hmac_secret")) {
                result = new IllegalStateException("opaque identity secret survived fixture cleanup");
            }
            if (result == null) {
                Log.i(TAG, "AIOS_CONTEXT_LIFECYCLE_SMOKE_OK");
            } else {
                Log.e(TAG, "AIOS_CONTEXT_LIFECYCLE_SMOKE_FAILED", result);
            }
            finish();
        }, 300L);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
