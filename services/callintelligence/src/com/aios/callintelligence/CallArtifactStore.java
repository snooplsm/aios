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

/** Credential-encrypted call artifacts with an immutable 24-hour expiry. */
final class CallArtifactStore {
    static final long RETENTION_MILLIS = 24L * 60L * 60L * 1000L;

    private final File callsDirectory;

    CallArtifactStore(Context context) {
        callsDirectory = new File(context.getFilesDir(), "calls");
    }

    synchronized Session create(String callId, boolean answeredByAi, long nowEpochMillis)
            throws IOException {
        if (!callsDirectory.isDirectory() && !callsDirectory.mkdirs()) {
            throw new IOException("cannot create private call directory");
        }
        File directory = new File(callsDirectory, digest(callId));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create private call session");
        }
        long expiresAt = Math.addExact(nowEpochMillis, RETENTION_MILLIS);
        writeMetadata(directory, nowEpochMillis, expiresAt, answeredByAi);
        return new Session(directory, expiresAt);
    }

    synchronized void cleanup(long nowEpochMillis) {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null) {
            return;
        }
        for (File directory : sessions) {
            long expiry = readExpiry(directory);
            if (expiry <= nowEpochMillis) {
                deleteTree(directory);
            }
        }
    }

    synchronized long nextExpiryEpochMillis() {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null || sessions.length == 0) {
            return Long.MAX_VALUE;
        }
        long next = Long.MAX_VALUE;
        for (File directory : sessions) {
            next = Math.min(next, readExpiry(directory));
        }
        return next;
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
            return new JSONObject(text).getLong("expires_at_epoch_ms");
        } catch (IOException | JSONException error) {
            // An unreadable session cannot be proven unexpired, so delete it.
            return Long.MIN_VALUE;
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

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // Best effort: a later cleanup retries failures.
        file.delete();
    }

    static final class Session implements AutoCloseable {
        private final File directory;
        final long expiresAtEpochMillis;
        private OutputStream downlink;
        private OutputStream uplink;

        Session(File directory, long expiresAtEpochMillis) {
            this.directory = directory;
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
                long observedAtEpochMillis)
                throws IOException {
            JSONObject value = new JSONObject();
            try {
                value.put("risk_score", riskScore);
                value.put("label", label);
                value.put("reason_code", reasonCode);
                value.put("source", source);
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

        @Override
        public synchronized void close() {
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
