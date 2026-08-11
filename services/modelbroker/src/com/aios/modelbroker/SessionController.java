package com.aios.modelbroker;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Owns public session IDs, callback death, queued inputs, and runtime leases. */
final class SessionController implements AutoCloseable {
    private static final int MAX_PENDING_INPUTS = 4;
    private static final int MAX_TEXT_CHARS = 256 * 1024;
    private static final int MAX_CHUNK_CHARS = 64 * 1024;
    private static final int MAX_RESULT_CHARS = 1024 * 1024;

    private interface PendingInput extends AutoCloseable {
        boolean accepts(String capability);

        void dispatch(RuntimeAdapter.Session session) throws RemoteException;

        @Override
        void close();
    }

    private static final class TextInput implements PendingInput {
        final String text;
        final boolean endOfInput;

        TextInput(String text, boolean endOfInput) {
            this.text = text;
            this.endOfInput = endOfInput;
        }

        @Override
        public boolean accepts(String capability) {
            return "text_generation".equals(capability)
                    || "call_classification".equals(capability)
                    || "call_summary".equals(capability)
                    || "speech_synthesis".equals(capability);
        }

        @Override
        public void dispatch(RuntimeAdapter.Session session) throws RemoteException {
            session.submitText(text, endOfInput);
        }

        @Override
        public void close() {}
    }

    private static final class AudioInput implements PendingInput {
        ParcelFileDescriptor descriptor;
        final AudioStreamFormat format;
        final boolean endOfInput;

        AudioInput(
                ParcelFileDescriptor descriptor,
                AudioStreamFormat format,
                boolean endOfInput) {
            this.descriptor = descriptor;
            this.format = format;
            this.endOfInput = endOfInput;
        }

        @Override
        public boolean accepts(String capability) {
            return "streaming_asr".equals(capability)
                    || "audio_understanding".equals(capability);
        }

        @Override
        public void dispatch(RuntimeAdapter.Session session) throws RemoteException {
            session.submitAudio(descriptor, format, endOfInput);
        }

        @Override
        public void close() {
            closeDescriptor(descriptor);
            descriptor = null;
        }
    }

    private static final class AudioOutput implements PendingInput {
        ParcelFileDescriptor descriptor;
        final AudioStreamFormat format;

        AudioOutput(ParcelFileDescriptor descriptor, AudioStreamFormat format) {
            this.descriptor = descriptor;
            this.format = format;
        }

        @Override
        public boolean accepts(String capability) {
            return "speech_synthesis".equals(capability);
        }

        @Override
        public void dispatch(RuntimeAdapter.Session session) throws RemoteException {
            session.attachAudioOutput(descriptor, format);
        }

        @Override
        public void close() {
            closeDescriptor(descriptor);
            descriptor = null;
        }
    }

    private static final class MediaInput implements PendingInput {
        ParcelFileDescriptor descriptor;
        final String mimeType;
        final boolean endOfInput;

        MediaInput(ParcelFileDescriptor descriptor, String mimeType, boolean endOfInput) {
            this.descriptor = descriptor;
            this.mimeType = mimeType;
            this.endOfInput = endOfInput;
        }

        @Override
        public boolean accepts(String capability) {
            return "image_understanding".equals(capability)
                    || "video_understanding".equals(capability)
                    || "audio_understanding".equals(capability);
        }

        @Override
        public void dispatch(RuntimeAdapter.Session session) throws RemoteException {
            session.submitMedia(descriptor, mimeType, endOfInput);
        }

        @Override
        public void close() {
            closeDescriptor(descriptor);
            descriptor = null;
        }
    }

    private static final class Record {
        final long id;
        final int ownerUid;
        final RuntimeActivationState activation;
        final ModelRequest request;
        final IModelCallback callback;
        final IBinder.DeathRecipient deathRecipient;
        final long createdAtElapsedMillis;
        final Object callbackLock = new Object();
        final ArrayDeque<PendingInput> pending = new ArrayDeque<>();
        VerifiedArtifact artifact;
        RuntimeAdapter.Session runtimeSession;
        boolean audioOutputAttached;
        boolean terminal;
        long lastChunkSequence = -1L;
        long chunkCount;
        long chunkChars;

        Record(
                long id,
                int ownerUid,
                List<VerifiedArtifact> candidates,
                ModelRequest request,
                IModelCallback callback,
                IBinder.DeathRecipient deathRecipient,
                long createdAtElapsedMillis) {
            this.id = id;
            this.ownerUid = ownerUid;
            activation = new RuntimeActivationState(candidates);
            this.request = request;
            this.callback = callback;
            this.deathRecipient = deathRecipient;
            this.createdAtElapsedMillis = createdAtElapsedMillis;
        }
    }

    private static final class PreparedChange {
        final SessionArbiter.Change change;
        final List<Record> cancelled;

        PreparedChange(SessionArbiter.Change change, List<Record> cancelled) {
            this.change = change;
            this.cancelled = cancelled;
        }
    }

    private final AtomicLong nextId = new AtomicLong(1L);
    private final RuntimeRegistry runtimes;
    private final SessionArbiter arbiter;
    private final Map<Long, Record> records = new HashMap<>();
    private final SessionDeadlineQueue deadlines = new SessionDeadlineQueue();
    private final ScheduledThreadPoolExecutor deadlineExecutor;
    private ScheduledFuture<?> deadlineFuture;
    private long deadlineGeneration;
    private boolean closed;

    SessionController(RuntimeRegistry runtimes, int capacity) {
        this.runtimes = runtimes;
        arbiter = new SessionArbiter(capacity);
        deadlineExecutor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "aios-model-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        deadlineExecutor.setRemoveOnCancelPolicy(true);
    }

    long create(
            int ownerUid,
            AuthorizedClientPolicy.Rule client,
            List<VerifiedArtifact> candidates,
            ModelRequest request,
            IModelCallback callback) {
        if (candidates == null || candidates.isEmpty()) {
            notifyError(callback, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "at least one admitted runtime candidate is required");
            return -1L;
        }
        long nowMillis = SystemClock.elapsedRealtime();
        if (!SessionDeadlinePolicy.validAt(
                request.capability,
                request.deadlineElapsedRealtimeMillis,
                nowMillis)) {
            notifyError(callback, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "request has an invalid elapsed-realtime deadline mode");
            return -1L;
        }
        long id = nextId.getAndIncrement();
        IBinder.DeathRecipient deathRecipient = () -> cancelAfterClientDeath(id, ownerUid);
        Record record = new Record(
                id, ownerUid, candidates, request, callback, deathRecipient, nowMillis);
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException error) {
            return -1L;
        }

        PreparedChange prepared;
        synchronized (this) {
            if (closed) {
                callback.asBinder().unlinkToDeath(deathRecipient, 0);
                notifyError(callback, ModelBrokerService.ERROR_NOT_READY, "broker is stopping");
                return -1L;
            }
            SessionArbiter.Change change = arbiter.submit(
                    id,
                    ownerUid,
                    WorkClass.fromAuthorizedWorkload(request.workload),
                    client.maxSessions);
            if (change.submittedStatus == SessionArbiter.Status.REJECTED_QUOTA) {
                callback.asBinder().unlinkToDeath(deathRecipient, 0);
                notifyError(callback, ModelBrokerService.ERROR_BUSY,
                        "client session quota exceeded");
                return -1L;
            }
            records.put(id, record);
            if (SessionDeadlinePolicy.shouldTrack(
                    request.capability, request.deadlineElapsedRealtimeMillis)) {
                deadlines.add(id, request.deadlineElapsedRealtimeMillis);
                rescheduleDeadlineLocked();
            }
            prepared = prepareChangeLocked(change);
        }
        apply(prepared);
        return id;
    }

    void submitText(int ownerUid, long sessionId, String text, boolean endOfInput) {
        if (text == null || text.length() > MAX_TEXT_CHARS) {
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "text input is absent or too large");
            return;
        }
        submit(ownerUid, sessionId, new TextInput(text, endOfInput));
    }

    void submitAudio(
            int ownerUid,
            long sessionId,
            ParcelFileDescriptor pcmStream,
            AudioStreamFormat format,
            boolean endOfInput) {
        if (pcmStream == null || format == null || format.sampleRateHz <= 0
                || (format.sampleRateHz != 8_000 && format.sampleRateHz != 16_000
                && format.sampleRateHz != 48_000)
                || format.channelCount != 1 || format.pcmEncoding != 2
                || !("downlink".equals(format.direction)
                || "uplink".equals(format.direction)
                || "media".equals(format.direction))) {
            closeDescriptor(pcmStream);
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "invalid PCM stream format");
            return;
        }
        submit(ownerUid, sessionId, new AudioInput(pcmStream, format, endOfInput));
    }

    void attachAudioOutput(
            int ownerUid,
            long sessionId,
            ParcelFileDescriptor pcmSink,
            AudioStreamFormat format) {
        if (pcmSink == null || format == null
                || (format.sampleRateHz != 16_000 && format.sampleRateHz != 22_050
                && format.sampleRateHz != 24_000 && format.sampleRateHz != 48_000)
                || format.channelCount != 1 || format.pcmEncoding != 2
                || !"synthesis".equals(format.direction)) {
            closeDescriptor(pcmSink);
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "invalid synthesis PCM output format");
            return;
        }
        boolean duplicate;
        synchronized (this) {
            Record record = records.get(sessionId);
            if (record == null) {
                closeDescriptor(pcmSink);
                throw new SecurityException("session is absent");
            }
            requireOwner(record, ownerUid);
            duplicate = record.audioOutputAttached;
            if (!duplicate) {
                record.audioOutputAttached = true;
            }
        }
        if (duplicate) {
            closeDescriptor(pcmSink);
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "synthesis PCM output is already attached");
            return;
        }
        submit(ownerUid, sessionId, new AudioOutput(pcmSink, format));
    }

    void submitMedia(
            int ownerUid,
            long sessionId,
            ParcelFileDescriptor media,
            String mimeType,
            boolean endOfInput) {
        if (media == null || mimeType == null
                || !(mimeType.startsWith("image/") || mimeType.startsWith("audio/"))) {
            closeDescriptor(media);
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "unsupported or absent media input");
            return;
        }
        submit(ownerUid, sessionId, new MediaInput(media, mimeType, endOfInput));
    }

    void cancel(int ownerUid, long sessionId) {
        Record record;
        PreparedChange prepared;
        synchronized (this) {
            record = records.get(sessionId);
            if (record == null) {
                return;
            }
            requireOwner(record, ownerUid);
            records.remove(sessionId);
            removeDeadlineLocked(sessionId);
            prepared = prepareChangeLocked(arbiter.finish(sessionId, ownerUid));
        }
        dispose(record, false, 0, null, true);
        apply(prepared);
    }

    void setCallActive(boolean active) {
        PreparedChange prepared;
        synchronized (this) {
            prepared = prepareChangeLocked(arbiter.setCallActive(active));
        }
        apply(prepared);
    }

    void onMemoryPressure() {
        PreparedChange prepared;
        synchronized (this) {
            prepared = prepareChangeLocked(arbiter.preemptBackgroundForMemoryPressure());
        }
        apply(prepared);
    }

    @Override
    public void close() {
        List<Record> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = new ArrayList<>(records.values());
            records.clear();
            deadlines.clear();
            cancelDeadlineWakeupLocked();
        }
        deadlineExecutor.shutdownNow();
        for (Record record : snapshot) {
            dispose(record, true, ModelBrokerService.ERROR_NOT_READY,
                    "broker is stopping", true);
        }
    }

    private void submit(int ownerUid, long sessionId, PendingInput input) {
        RuntimeAdapter.Session runtime;
        boolean invalidType = false;
        boolean overflow = false;
        synchronized (this) {
            Record record = records.get(sessionId);
            if (record == null) {
                input.close();
                throw new SecurityException("session is absent");
            }
            if (record.ownerUid != ownerUid) {
                input.close();
                throw new SecurityException("session is owned by another UID");
            }
            if (!input.accepts(record.request.capability)) {
                invalidType = true;
                runtime = null;
            } else {
                runtime = record.runtimeSession;
            }
            if (!invalidType && runtime == null) {
                if (record.pending.size() >= MAX_PENDING_INPUTS) {
                    overflow = true;
                } else {
                    record.pending.add(input);
                }
            }
        }
        if (invalidType) {
            input.close();
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_INVALID_REQUEST,
                    "input type does not match the requested capability");
            return;
        }
        if (overflow) {
            input.close();
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_BUSY,
                    "too many queued inputs");
            return;
        }
        if (runtime == null) {
            return;
        }
        dispatch(ownerUid, sessionId, runtime, input);
    }

    private void dispatch(
            int ownerUid,
            long sessionId,
            RuntimeAdapter.Session session,
            PendingInput input) {
        try {
            input.dispatch(session);
        } catch (RemoteException | RuntimeException error) {
            failOwned(ownerUid, sessionId, ModelBrokerService.ERROR_RUNTIME_FAILED,
                    "runtime input failed");
        } finally {
            input.close();
        }
    }

    private PreparedChange prepareChangeLocked(SessionArbiter.Change change) {
        List<Record> cancelled = new ArrayList<>();
        for (long id : change.cancelled) {
            Record record = records.remove(id);
            if (record != null) {
                cancelled.add(record);
                deadlines.remove(id);
            }
        }
        if (!cancelled.isEmpty()) {
            rescheduleDeadlineLocked();
        }
        return new PreparedChange(change, cancelled);
    }

    private void apply(PreparedChange prepared) {
        for (Record record : prepared.cancelled) {
            dispose(record, true, ModelBrokerService.ERROR_PREEMPTED,
                    "session preempted by call inference", true);
        }
        for (long id : prepared.change.activated) {
            activate(id);
        }
    }

    private void activate(long id) {
        Record record;
        synchronized (this) {
            record = records.get(id);
            if (record == null || record.runtimeSession != null) {
                return;
            }
        }

        while (true) {
            RuntimeActivationState.Attempt attempt;
            synchronized (this) {
                if (records.get(id) != record || record.runtimeSession != null) {
                    return;
                }
                attempt = record.activation.beginNext();
            }
            if (attempt == null) break;
            VerifiedArtifact candidate = attempt.artifact;

            RuntimeAdapter.Session runtime;
            try {
                runtime = runtimes.open(
                        candidate, record.request, callbackFor(record, attempt));
            } catch (IOException | RemoteException | RuntimeException error) {
                synchronized (this) {
                    record.activation.reject(attempt);
                }
                continue;
            }

            List<PendingInput> pending = null;
            boolean accepted = false;
            boolean recordGone = false;
            synchronized (this) {
                recordGone = records.get(id) != record;
                if (!recordGone
                        && record.runtimeSession == null
                        && record.activation.accept(attempt)) {
                    record.artifact = candidate;
                    record.runtimeSession = runtime;
                    pending = new ArrayList<>(record.pending);
                    record.pending.clear();
                    accepted = true;
                } else {
                    record.activation.reject(attempt);
                }
            }
            if (!accepted) {
                runtime.close();
                if (recordGone) {
                    return;
                }
                continue;
            }
            for (PendingInput input : pending) {
                dispatch(record.ownerUid, id, runtime, input);
            }
            return;
        }

        failOwned(record.ownerUid, id, ModelBrokerService.ERROR_RUNTIME_FAILED,
                "every admitted runtime candidate failed to start");
    }

    private IModelCallback callbackFor(
            Record record, RuntimeActivationState.Attempt activationAttempt) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (!acceptRuntimeCallback(record, activationAttempt)) {
                    return;
                }
                boolean invalid = false;
                boolean clientDied = false;
                synchronized (record.callbackLock) {
                    if (record.terminal) {
                        return;
                    }
                    if (!acceptChunk(record, chunk)) {
                        invalid = true;
                    } else {
                        try {
                            record.callback.onChunk(chunk);
                        } catch (RemoteException error) {
                            clientDied = true;
                        }
                    }
                }
                if (invalid) {
                    failOwned(record.ownerUid, record.id,
                            ModelBrokerService.ERROR_RUNTIME_FAILED,
                            "runtime returned an invalid chunk");
                    return;
                }
                if (clientDied) {
                    cancelAfterClientDeath(record.id, record.ownerUid);
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
                if (!acceptRuntimeCallback(record, activationAttempt)) {
                    return;
                }
                completeFromRuntime(record, result);
            }

            @Override
            public void onError(int code, String message) {
                if (!acceptRuntimeCallback(record, activationAttempt)) {
                    return;
                }
                int safeCode = code == ModelBrokerService.ERROR_INVALID_REQUEST
                        || code == ModelBrokerService.ERROR_BUSY
                        ? code : ModelBrokerService.ERROR_RUNTIME_FAILED;
                String safeMessage = message == null
                        ? "runtime provider failed"
                        : message.substring(0, Math.min(message.length(), 256));
                failFromRuntime(record, safeCode, safeMessage);
            }
        };
    }

    private synchronized boolean acceptRuntimeCallback(
            Record record, RuntimeActivationState.Attempt activationAttempt) {
        if (records.get(record.id) != record) return false;
        return record.activation.allowCallback(activationAttempt)
                && record.runtimeSession != null;
    }

    private void failOwned(int ownerUid, long sessionId, int code, String message) {
        Record record;
        PreparedChange prepared;
        synchronized (this) {
            record = records.get(sessionId);
            if (record == null) {
                return;
            }
            requireOwner(record, ownerUid);
            records.remove(sessionId);
            removeDeadlineLocked(sessionId);
            prepared = prepareChangeLocked(arbiter.finish(sessionId, ownerUid));
        }
        dispose(record, true, code, message, true);
        apply(prepared);
    }

    private void completeFromRuntime(Record record, InferenceResult result) {
        PreparedChange prepared = claimRuntimeTerminal(record);
        if (prepared == null) {
            return;
        }
        synchronized (record.callbackLock) {
            if (!record.terminal) {
                record.terminal = true;
                try {
                    if (validResult(record, result)) {
                        record.callback.onCompleted(result);
                    } else {
                        record.callback.onError(ModelBrokerService.ERROR_RUNTIME_FAILED,
                                "runtime returned an invalid result");
                    }
                } catch (RemoteException ignored) {
                    // Completion still releases the lease.
                }
            }
        }
        cleanup(record, false);
        apply(prepared);
    }

    private void failFromRuntime(Record record, int code, String message) {
        PreparedChange prepared = claimRuntimeTerminal(record);
        if (prepared == null) {
            return;
        }
        dispose(record, true, code, message, false);
        apply(prepared);
    }

    private PreparedChange claimRuntimeTerminal(Record record) {
        synchronized (this) {
            if (records.get(record.id) != record) {
                return null;
            }
            records.remove(record.id);
            removeDeadlineLocked(record.id);
            return prepareChangeLocked(arbiter.finish(record.id, record.ownerUid));
        }
    }

    private void cancelAfterClientDeath(long sessionId, int ownerUid) {
        try {
            cancel(ownerUid, sessionId);
        } catch (SecurityException ignored) {
            // A completed/preempted session is already gone.
        }
    }

    private synchronized boolean acceptChunk(Record record, GenerationChunk chunk) {
        if (records.get(record.id) != record || chunk == null || chunk.text == null
                || chunk.text.length() > MAX_CHUNK_CHARS
                || chunk.language == null
                || !record.artifact.languages.contains(chunk.language)
                || chunk.sequence <= record.lastChunkSequence
                || !Float.isFinite(chunk.confidence)
                || chunk.confidence < 0.0f || chunk.confidence > 1.0f
                || chunk.sourceStartMillis < 0L
                || chunk.sourceEndMillis < chunk.sourceStartMillis) {
            return false;
        }
        long nowMillis = SystemClock.elapsedRealtime();
        if (nowMillis < record.createdAtElapsedMillis
                || !SessionChunkPolicy.accepts(
                record.request.workload,
                SessionDeadlinePolicy.isLifecycleBound(
                        record.request.capability,
                        record.request.deadlineElapsedRealtimeMillis),
                record.chunkCount,
                record.chunkChars,
                chunk.text.length(),
                chunk.sourceEndMillis,
                nowMillis - record.createdAtElapsedMillis)) {
            return false;
        }
        record.lastChunkSequence = chunk.sequence;
        record.chunkCount++;
        record.chunkChars = record.chunkChars > Long.MAX_VALUE - chunk.text.length()
                ? Long.MAX_VALUE : record.chunkChars + chunk.text.length();
        return true;
    }

    private static boolean validResult(Record record, InferenceResult result) {
        boolean fieldsValid = result != null
                && record.request.requestId.equals(result.requestId)
                && record.request.capability.equals(result.capability)
                && record.artifact.modelId.equals(result.modelId)
                && record.artifact.sha256.equals(result.modelDigest)
                && record.artifact.languages.contains(result.language)
                && result.outputJson != null
                && result.outputJson.length() <= MAX_RESULT_CHARS
                && result.elapsedMillis >= 0L;
        if (!fieldsValid) {
            return false;
        }
        try {
            new JSONObject(result.outputJson);
            return true;
        } catch (JSONException error) {
            return false;
        }
    }

    private static void dispose(
            Record record,
            boolean reportError,
            int errorCode,
            String message,
            boolean closeRuntime) {
        synchronized (record.callbackLock) {
            if (!record.terminal) {
                record.terminal = true;
                if (reportError) {
                    notifyError(record.callback, errorCode, message);
                }
            }
        }
        cleanup(record, closeRuntime);
    }

    private static void cleanup(Record record, boolean closeRuntime) {
        record.callback.asBinder().unlinkToDeath(record.deathRecipient, 0);
        for (PendingInput input : record.pending) {
            input.close();
        }
        record.pending.clear();
        if (closeRuntime && record.runtimeSession != null) {
            record.runtimeSession.close();
        }
    }

    private void removeDeadlineLocked(long sessionId) {
        if (deadlines.remove(sessionId)) {
            rescheduleDeadlineLocked();
        }
    }

    private void rescheduleDeadlineLocked() {
        cancelDeadlineWakeupLocked();
        if (closed) {
            return;
        }
        long delayMillis = deadlines.millisUntilNext(SystemClock.elapsedRealtime());
        if (delayMillis == Long.MAX_VALUE) {
            return;
        }
        long generation = deadlineGeneration;
        deadlineFuture = deadlineExecutor.schedule(
                () -> expireDeadlines(generation), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelDeadlineWakeupLocked() {
        deadlineGeneration++;
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
    }

    private void expireDeadlines(long generation) {
        List<Record> expired = new ArrayList<>();
        List<PreparedChange> changes = new ArrayList<>();
        synchronized (this) {
            if (closed || generation != deadlineGeneration) {
                return;
            }
            deadlineFuture = null;
            for (long id : deadlines.removeExpired(SystemClock.elapsedRealtime())) {
                Record record = records.remove(id);
                if (record != null) {
                    expired.add(record);
                    changes.add(prepareChangeLocked(arbiter.finish(id, record.ownerUid)));
                }
            }
            rescheduleDeadlineLocked();
        }
        for (Record record : expired) {
            dispose(record, true, ModelBrokerService.ERROR_DEADLINE_EXCEEDED,
                    "session deadline exceeded", true);
        }
        for (PreparedChange change : changes) {
            apply(change);
        }
    }

    private static void requireOwner(Record record, int ownerUid) {
        if (record.ownerUid != ownerUid) {
            throw new SecurityException("session is owned by another UID");
        }
    }

    private static void notifyError(IModelCallback callback, int code, String message) {
        try {
            callback.onError(code, message);
        } catch (RemoteException ignored) {
            // Client death already ended the session.
        }
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort while unwinding a request.
        }
    }
}
