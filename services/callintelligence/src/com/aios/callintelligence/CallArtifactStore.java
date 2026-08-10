package com.aios.callintelligence;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/** Credential-encrypted call artifacts with an immutable 24-hour expiry. */
final class CallArtifactStore {
    private static final Object STORAGE_LOCK = new Object();
    private static final Map<File, Session> ACTIVE_SESSIONS = new HashMap<>();

    private final File callsDirectory;

    CallArtifactStore(Context context) {
        callsDirectory = new File(context.getFilesDir(), "calls");
    }

    Session create(String callId, boolean answeredByAi, long nowEpochMillis)
            throws IOException {
        synchronized (STORAGE_LOCK) {
            if (!callsDirectory.isDirectory() && !callsDirectory.mkdirs()) {
                throw new IOException("cannot create private call directory");
            }
            String sourceId = digest(callId);
            File directory = new File(callsDirectory, sourceId);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("cannot create private call session");
            }
            long expiresAt = CallArtifactRetention.expiresAt(nowEpochMillis);
            writeMetadata(directory, nowEpochMillis, expiresAt, answeredByAi);
            Session session = new Session(
                    directory, sourceId, nowEpochMillis, expiresAt);
            ACTIVE_SESSIONS.put(directory.getAbsoluteFile(), session);
            return session;
        }
    }

    void cleanup(long nowEpochMillis) {
        synchronized (STORAGE_LOCK) {
            CallArtifactRetention.cleanup(callsDirectory, nowEpochMillis,
                    CallArtifactStore::readExpiry,
                    CallArtifactStore::closeActiveSession);
        }
    }

    long nextExpiryEpochMillis() {
        synchronized (STORAGE_LOCK) {
            return CallArtifactRetention.nextExpiry(callsDirectory,
                    CallArtifactStore::readExpiry);
        }
    }

    private static void writeMetadata(
            File directory, long createdAt, long expiresAt, boolean answeredByAi)
            throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("schema_version", 1);
            json.put("created_at_epoch_ms", createdAt);
            json.put("expires_at_epoch_ms", expiresAt);
            json.put("answered_by_ai", answeredByAi);
        } catch (JSONException impossible) {
            throw new IOException("cannot encode session metadata", impossible);
        }

        AtomicFile file = new AtomicFile(new File(directory, "session.json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) {
                file.failWrite(stream);
            }
            throw error;
        }
    }

    private static long readExpiry(File directory) {
        try {
            AtomicFile file = new AtomicFile(new File(directory, "session.json"));
            String text = new String(file.readFully(), StandardCharsets.UTF_8);
            JSONObject metadata = new JSONObject(text);
            return CallArtifactRetention.validatedExpiry(
                    metadata.getLong("created_at_epoch_ms"),
                    metadata.getLong("expires_at_epoch_ms"));
        } catch (IOException | JSONException error) {
            // An unreadable session cannot be proven unexpired, so delete it.
            return CallArtifactRetention.UNREADABLE_EXPIRY;
        }
    }

    private static String digest(String value) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static void closeActiveSession(File directory) {
        Session active = ACTIVE_SESSIONS.remove(directory.getAbsoluteFile());
        if (active != null) {
            active.closeStreams();
        }
    }

    static final class Session implements AutoCloseable {
        private final File directory;
        final String sourceId;
        final long createdAtEpochMillis;
        final long expiresAtEpochMillis;
        private OutputStream downlink;
        private OutputStream uplink;

        Session(
                File directory,
                String sourceId,
                long createdAtEpochMillis,
                long expiresAtEpochMillis) {
            this.directory = directory;
            this.sourceId = sourceId;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }

        synchronized OutputStream openDownlink() throws IOException {
            if (downlink == null) {
                downlink = new BufferedOutputStream(
                        new FileOutputStream(new File(directory, "rx.pcm")), 64 * 1024);
            }
            return downlink;
        }

        synchronized OutputStream openUplink() throws IOException {
            if (uplink == null) {
                uplink = new BufferedOutputStream(
                        new FileOutputStream(new File(directory, "tx.pcm")), 64 * 1024);
            }
            return uplink;
        }

        synchronized void appendTranscript(
                String direction,
                String language,
                String text,
                boolean isFinal,
                float confidence,
                long startMillis,
                long endMillis) throws IOException {
            JSONObject value = new JSONObject();
            try {
                value.put("direction", direction);
                value.put("language", language);
                value.put("text", text);
                value.put("is_final", isFinal);
                value.put("confidence", confidence);
                value.put("start_ms", startMillis);
                value.put("end_ms", endMillis);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode transcript segment", impossible);
            }
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, "transcript.jsonl"), true)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
                stream.getFD().sync();
            }
        }

        synchronized void appendAssessment(
                int riskScore,
                String label,
                String reasonCode,
                String source,
                long revision,
                long observedAtEpochMillis)
                throws IOException {
            JSONObject value = new JSONObject();
            try {
                value.put("risk_score", riskScore);
                value.put("label", label);
                value.put("reason_code", reasonCode);
                value.put("source", source);
                value.put("revision", revision);
                value.put("observed_at_epoch_ms", observedAtEpochMillis);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode call assessment", impossible);
            }
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, "assessments.jsonl"), true)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
                stream.getFD().sync();
            }
        }

        synchronized void appendAssistantReply(
                String language,
                String text,
                long observedAtEpochMillis) throws IOException {
            JSONObject value = new JSONObject();
            try {
                value.put("direction", "assistant");
                value.put("language", language);
                value.put("text", text);
                value.put("observed_at_epoch_ms", observedAtEpochMillis);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode assistant reply", impossible);
            }
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, "dialogue.jsonl"), true)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
                stream.getFD().sync();
            }
        }

        synchronized void appendAssistantState(
                boolean aiHandling,
                long revision,
                long observedAtEpochMillis) throws IOException {
            JSONObject value = new JSONObject();
            try {
                value.put("ai_handling", aiHandling);
                value.put("revision", revision);
                value.put("observed_at_epoch_ms", observedAtEpochMillis);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode assistant state", impossible);
            }
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, "assistant_state.jsonl"), true)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
                stream.getFD().sync();
            }
        }

        @Override
        public void close() {
            closeStreams();
            synchronized (STORAGE_LOCK) {
                ACTIVE_SESSIONS.remove(directory.getAbsoluteFile(), this);
            }
        }

        private synchronized void closeStreams() {
            closeQuietly(downlink);
            closeQuietly(uplink);
            downlink = null;
            uplink = null;
        }

        private static void closeQuietly(OutputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // The retention sweep owns final cleanup.
            }
        }
    }
}
