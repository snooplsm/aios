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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Credential-encrypted call artifacts with a 24-hour maximum and emergency erasure. */
final class CallArtifactStore {
    private static final class SessionMetadata {
        final CallArtifactRetention.Deadline deadline;
        final boolean answeredByAi;

        SessionMetadata(
                CallArtifactRetention.Deadline deadline,
                boolean answeredByAi) {
            this.deadline = deadline;
            this.answeredByAi = answeredByAi;
        }
    }

    private static final Object STORAGE_LOCK = new Object();
    private static final Map<File, Session> ACTIVE_SESSIONS = new HashMap<>();

    private final Context context;
    private final File callsDirectory;

    CallArtifactStore(Context context) {
        this.context = context.getApplicationContext();
        callsDirectory = new File(this.context.getFilesDir(), "calls");
    }

    Session create(String callId, boolean answeredByAi, long nowEpochMillis)
            throws IOException {
        synchronized (STORAGE_LOCK) {
            RetentionClock.Snapshot now = RetentionClock.capture(context, nowEpochMillis);
            if (!callsDirectory.isDirectory() && !callsDirectory.mkdirs()) {
                throw new IOException("cannot create private call directory");
            }
            String sourceId = digest(callId);
            File directory = new File(callsDirectory, sourceId);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("cannot create private call session");
            }
            CallArtifactRetention.Deadline deadline = CallArtifactRetention.Deadline.create(
                    now.bootIdentity, now.epochMillis, now.elapsedRealtimeMillis);
            boolean storedAnsweredByAi = false;
            try {
                SessionMetadata existing = readMetadata(directory);
                if (CallArtifactRetention.canResume(
                        existing.deadline,
                        now.bootIdentity,
                        now.epochMillis,
                        now.elapsedRealtimeMillis)) {
                    deadline = existing.deadline;
                    storedAnsweredByAi = existing.answeredByAi;
                } else {
                    resetDirectory(directory);
                }
            } catch (IOException | JSONException unreadable) {
                resetDirectory(directory);
            }
            writeMetadata(directory, deadline, storedAnsweredByAi || answeredByAi);
            Session session = new Session(
                    directory,
                    sourceId,
                    deadline.createdAtEpochMillis,
                    deadline.expiresAtEpochMillis,
                    deadline.bootIdentity,
                    deadline.createdAtElapsedRealtimeMillis,
                    deadline.expiresAtElapsedRealtimeMillis);
            ACTIVE_SESSIONS.put(directory.getAbsoluteFile(), session);
            return session;
        }
    }

    void cleanup(long nowEpochMillis) {
        synchronized (STORAGE_LOCK) {
            RetentionClock.Snapshot now = RetentionClock.capture(context, nowEpochMillis);
            CallArtifactRetention.cleanup(
                    callsDirectory,
                    now.bootIdentity,
                    now.epochMillis,
                    now.elapsedRealtimeMillis,
                    CallArtifactStore::readDeadline,
                    CallArtifactStore::closeActiveSession);
        }
    }

    long nextExpiryElapsedRealtimeMillis() {
        synchronized (STORAGE_LOCK) {
            RetentionClock.Snapshot now = RetentionClock.capture(
                    context, System.currentTimeMillis());
            return CallArtifactRetention.nextElapsedAlarm(
                    callsDirectory,
                    now.bootIdentity,
                    now.epochMillis,
                    now.elapsedRealtimeMillis,
                    CallArtifactStore::readDeadline);
        }
    }

    /** Stops active writers and removes every artifact for one opaque call ID. */
    void discard(String callId) throws IOException {
        String sourceId = digest(callId);
        synchronized (STORAGE_LOCK) {
            File directory = new File(callsDirectory, sourceId).getAbsoluteFile();
            closeActiveSession(directory);
            if (!CallArtifactRetention.deleteTree(directory)) {
                throw new IOException("cannot erase emergency call artifact");
            }
        }
    }

    private static void writeMetadata(
            File directory,
            CallArtifactRetention.Deadline deadline,
            boolean answeredByAi)
            throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("schema_version", 2);
            json.put("boot_identity", deadline.bootIdentity);
            json.put("created_at_epoch_ms", deadline.createdAtEpochMillis);
            json.put("expires_at_epoch_ms", deadline.expiresAtEpochMillis);
            json.put(
                    "created_at_elapsed_realtime_ms",
                    deadline.createdAtElapsedRealtimeMillis);
            json.put(
                    "expires_at_elapsed_realtime_ms",
                    deadline.expiresAtElapsedRealtimeMillis);
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

    private static CallArtifactRetention.Deadline readDeadline(File directory) {
        try {
            return readMetadata(directory).deadline;
        } catch (IOException | JSONException error) {
            // An unreadable session cannot be proven unexpired, so delete it.
            return CallArtifactRetention.Deadline.unreadable();
        }
    }

    private static SessionMetadata readMetadata(File directory)
            throws IOException, JSONException {
        AtomicFile file = new AtomicFile(new File(directory, "session.json"));
        String text = new String(file.readFully(), StandardCharsets.UTF_8);
        JSONObject metadata = new JSONObject(text);
        if (metadata.getInt("schema_version") != 2) {
            throw new JSONException("unsupported call artifact metadata schema");
        }
        return new SessionMetadata(
                new CallArtifactRetention.Deadline(
                        metadata.getString("boot_identity"),
                        metadata.getLong("created_at_epoch_ms"),
                        metadata.getLong("expires_at_epoch_ms"),
                        metadata.getLong("created_at_elapsed_realtime_ms"),
                        metadata.getLong("expires_at_elapsed_realtime_ms")),
                metadata.getBoolean("answered_by_ai"));
    }

    private static void resetDirectory(File directory) throws IOException {
        closeActiveSession(directory.getAbsoluteFile());
        if (!CallArtifactRetention.deleteTree(directory)
                || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("cannot reset private call session");
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
        private static final int MAX_CONTEXT_RECOVERY_BYTES = 256 * 1_024;
        private static final int MAX_CONTEXT_JOURNAL_LINE_CHARS = 16_384;

        private final File directory;
        final String sourceId;
        final long createdAtEpochMillis;
        final long expiresAtEpochMillis;
        final String expiryBootIdentity;
        final long createdAtElapsedRealtimeMillis;
        final long expiresAtElapsedRealtimeMillis;
        private OutputStream downlink;
        private OutputStream uplink;

        Session(
                File directory,
                String sourceId,
                long createdAtEpochMillis,
                long expiresAtEpochMillis,
                String expiryBootIdentity,
                long createdAtElapsedRealtimeMillis,
                long expiresAtElapsedRealtimeMillis) {
            this.directory = directory;
            this.sourceId = sourceId;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.expiryBootIdentity = expiryBootIdentity;
            this.createdAtElapsedRealtimeMillis = createdAtElapsedRealtimeMillis;
            this.expiresAtElapsedRealtimeMillis = expiresAtElapsedRealtimeMillis;
        }

        synchronized OutputStream openDownlink() throws IOException {
            if (downlink == null) {
                downlink = new BufferedOutputStream(
                        new FileOutputStream(new File(directory, "rx.pcm"), true), 64 * 1024);
            }
            return downlink;
        }

        synchronized OutputStream openUplink() throws IOException {
            if (uplink == null) {
                uplink = new BufferedOutputStream(
                        new FileOutputStream(new File(directory, "tx.pcm"), true), 64 * 1024);
            }
            return uplink;
        }

        /**
         * Reads only a bounded tail of the append-only transcript. A tail cut
         * starts after the next newline, so a split UTF-8 sequence or JSON
         * record can never become model context. Malformed/torn records are
         * ignored independently and the pure admission policy applies the
         * final role, language, turn, and total-memory bounds.
         */
        synchronized List<TranscriptContextRecovery.Turn> readConversationTail()
                throws IOException {
            File transcript = new File(directory, "transcript.jsonl");
            if (!transcript.isFile() || transcript.length() <= 0L) return List.of();
            long length = transcript.length();
            long start = Math.max(0L, length - MAX_CONTEXT_RECOVERY_BYTES);
            byte[] encoded = new byte[(int) (length - start)];
            try (RandomAccessFile stream = new RandomAccessFile(transcript, "r")) {
                stream.seek(start);
                stream.readFully(encoded);
            }
            String tail = new String(encoded, StandardCharsets.UTF_8);
            if (start > 0L) {
                int boundary = tail.indexOf('\n');
                if (boundary < 0) return List.of();
                tail = tail.substring(boundary + 1);
            }
            TranscriptContextRecovery recovery = new TranscriptContextRecovery();
            for (String line : tail.split("\\n")) {
                if (line.isBlank() || line.length() > MAX_CONTEXT_JOURNAL_LINE_CHARS) {
                    continue;
                }
                try {
                    JSONObject value = new JSONObject(line);
                    recovery.accept(
                            value.optString("direction", ""),
                            value.optString("language", ""),
                            value.optString("text", ""),
                            value.optBoolean("is_final", false));
                } catch (JSONException malformed) {
                    // A killed writer may leave one torn tail record. It does
                    // not invalidate earlier independently synced turns.
                }
            }
            return recovery.snapshot();
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
            appendJsonLine("transcript.jsonl", value);
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
            JSONObject transcript = new JSONObject();
            try {
                transcript.put("direction", "assistant");
                transcript.put("language", language);
                transcript.put("text", text);
                transcript.put("is_final", true);
                transcript.put("observed_at_epoch_ms", observedAtEpochMillis);
            } catch (JSONException impossible) {
                throw new IOException("cannot encode assistant transcript", impossible);
            }
            // Commit the unified conversation journal before the compatibility
            // dialogue record. This preserves the model's accepted reply for
            // continuity even if the process is lost during later TTS setup.
            appendJsonLine("transcript.jsonl", transcript);
            appendJsonLine("dialogue.jsonl", value);
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

        private void appendJsonLine(String name, JSONObject value) throws IOException {
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, name), true)) {
                stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
                stream.getFD().sync();
            }
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
