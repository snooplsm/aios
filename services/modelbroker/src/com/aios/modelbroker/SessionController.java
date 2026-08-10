package com.aios.modelbroker;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

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
import java.util.concurrent.atomic.AtomicLong;

/** Owns public session IDs, callback death, queued inputs, and runtime leases. */
final class SessionController implements AutoCloseable {
    private static final int MAX_PENDING_INPUTS = 4;
    private static final int MAX_TEXT_CHARS = 256 * 1024;
    private static final int MAX_CHUNK_CHARS = 64 * 1024;
    private static final int MAX_CHUNKS = 4096;
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
        final VerifiedArtifact artifact;
        final ModelRequest request;
        final IModelCallback callback;
        final IBinder.DeathRecipient deathRecipient;
        final ArrayDeque<PendingInput> pending = new ArrayDeque<>();
        RuntimeAdapter.Session runtimeSession;
        boolean audioOutputAttached;
        long lastChunkSequence = -1L;
        int chunkCount;

        Record(
                long id,
                int ownerUid,
                VerifiedArtifact artifact,
                ModelRequest request,
                IModelCallback callback,
                IBinder.DeathRecipient deathRecipient) {
            this.id = id;
            this.ownerUid = ownerUid;
            this.artifact = artifact;
            this.request = request;
            this.callback = callback;
            this.deathRecipient = deathRecipient;
        }
    }

    private final AtomicLong nextId = new AtomicLong(1L);
    private final RuntimeRegistry runtimes;
    private final SessionArbiter arbiter;
    private final Map<Long, Record> records = new HashMap<>();
    private boolean closed;

    SessionController(RuntimeRegistry runtimes, int capacity) {
        this.runtimes = runtimes;
        arbiter = new SessionArbiter(capacity);
    }

    long create(
            int ownerUid,
            AuthorizedClientPolicy.Rule client,
            VerifiedArtifact artifact,
            ModelRequest request,
            IModelCallback callback) {
        long id = nextId.getAndIncrement();
        IBinder.DeathRecipient deathRecipient = () -> cancelAfterClientDeath(id, ownerUid);
        Record record = new Record(
                id, ownerUid, artifact, request, callback, deathRecipient);
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException error) {
            return -1L;
        }

        SessionArbiter.Change change;
        synchronized (this) {
            if (closed) {
                callback.asBinder().unlinkToDeath(deathRecipient, 0);
                notifyError(callback, ModelBrokerService.ERROR_NOT_READY, "broker is stopping");
                return -1L;
            }
            change = arbiter.submit(
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
        }
        apply(change);
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
                || "uplink".equals(format.direction))) {
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
        SessionArbiter.Change change;
        synchronized (this) {
            record = records.get(sessionId);
            if (record == null) {
                return;
            }
            requireOwner(record, ownerUid);
            records.remove(sessionId);
            change = arbiter.finish(sessionId, ownerUid);
        }
        dispose(record, false, 0, null, true);
        apply(change);
    }

    void setCallActive(boolean active) {
        apply(arbiter.setCallActive(active));
    }

    void onMemoryPressure() {
        apply(arbiter.preemptBackgroundForMemoryPressure());
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
        }
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

    private void apply(SessionArbiter.Change change) {
        for (long id : change.cancelled) {
            Record record;
            synchronized (this) {
                record = records.remove(id);
            }
            if (record != null) {
                dispose(record, true, ModelBrokerService.ERROR_PREEMPTED,
                        "session preempted by call inference", true);
            }
        }
        for (long id : change.activated) {
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

        RuntimeAdapter.Session runtime;
        try {
            runtime = runtimes.open(record.artifact, record.request, callbackFor(record));
        } catch (IOException | RemoteException | RuntimeException error) {
            failOwned(record.ownerUid, id, ModelBrokerService.ERROR_RUNTIME_FAILED,
                    "runtime session could not start");
            return;
        }

        List<PendingInput> pending;
        synchronized (this) {
            if (records.get(id) != record) {
                runtime.close();
                return;
            }
            record.runtimeSession = runtime;
            pending = new ArrayList<>(record.pending);
            record.pending.clear();
        }
        for (PendingInput input : pending) {
            dispatch(record.ownerUid, id, runtime, input);
        }
    }

    private IModelCallback callbackFor(Record record) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (!acceptChunk(record, chunk)) {
                    failOwned(record.ownerUid, record.id,
                            ModelBrokerService.ERROR_RUNTIME_FAILED,
                            "runtime returned an invalid chunk");
                    return;
                }
                try {
                    record.callback.onChunk(chunk);
                } catch (RemoteException error) {
                    cancelAfterClientDeath(record.id, record.ownerUid);
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
                try {
                    if (validResult(record, result)) {
                        record.callback.onCompleted(result);
                    } else {
                        record.callback.onError(ModelBrokerService.ERROR_RUNTIME_FAILED,
                                "runtime returned an invalid result");
                    }
                } catch (RemoteException ignored) {
                    // Completion still releases the lease.
                } finally {
                    finishFromRuntime(record.id, record.ownerUid);
                }
            }

            @Override
            public void onError(int code, String message) {
                try {
                    int safeCode = code == ModelBrokerService.ERROR_INVALID_REQUEST
                            || code == ModelBrokerService.ERROR_BUSY
                            ? code : ModelBrokerService.ERROR_RUNTIME_FAILED;
                    String safeMessage = message == null
                            ? "runtime provider failed"
                            : message.substring(0, Math.min(message.length(), 256));
                    record.callback.onError(safeCode, safeMessage);
                } catch (RemoteException ignored) {
                    // Failure still releases the lease.
                } finally {
                    finishFromRuntime(record.id, record.ownerUid);
                }
            }
        };
    }

    private void failOwned(int ownerUid, long sessionId, int code, String message) {
        Record record;
        SessionArbiter.Change change;
        synchronized (this) {
            record = records.get(sessionId);
            if (record == null) {
                return;
            }
            requireOwner(record, ownerUid);
            records.remove(sessionId);
            change = arbiter.finish(sessionId, ownerUid);
        }
        dispose(record, true, code, message, true);
        apply(change);
    }

    private void finishFromRuntime(long sessionId, int ownerUid) {
        Record record;
        SessionArbiter.Change change;
        synchronized (this) {
            record = records.get(sessionId);
            if (record == null || record.ownerUid != ownerUid) {
                return;
            }
            records.remove(sessionId);
            change = arbiter.finish(sessionId, ownerUid);
        }
        dispose(record, false, 0, null, false);
        apply(change);
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
                || record.chunkCount >= MAX_CHUNKS
                || !Float.isFinite(chunk.confidence)
                || chunk.confidence < 0.0f || chunk.confidence > 1.0f
                || chunk.sourceStartMillis < 0L
                || chunk.sourceEndMillis < chunk.sourceStartMillis) {
            return false;
        }
        record.lastChunkSequence = chunk.sequence;
        record.chunkCount++;
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
        record.callback.asBinder().unlinkToDeath(record.deathRecipient, 0);
        for (PendingInput input : record.pending) {
            input.close();
        }
        record.pending.clear();
        if (closeRuntime && record.runtimeSession != null) {
            record.runtimeSession.close();
        }
        if (reportError) {
            notifyError(record.callback, errorCode, message);
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
