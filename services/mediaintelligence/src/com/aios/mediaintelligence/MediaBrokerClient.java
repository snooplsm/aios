package com.aios.mediaintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.GenerationChunk;
import com.aios.model.AudioStreamFormat;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One bounded media-understanding request for a JobService worker thread. */
final class MediaBrokerClient implements AutoCloseable {
    private static final String TAG = "AiosMediaBroker";
    private static final long BIND_TIMEOUT_SECONDS = 5L;
    private static final long INFERENCE_TIMEOUT_MINUTES = 2L;
    private static final long CONSTRAINT_RECHECK_MILLIS = 1_000L;
    private static final int ERROR_BROKER_UNAVAILABLE = 1;
    private static final int ERROR_INFERENCE_TIMEOUT = 2;
    private static final int ERROR_CONSTRAINT_BLOCKED = 3;

    static final class Result {
        final InferenceResult inference;
        final int errorCode;
        final String retryReason;
        final long inputPreparationMillis;
        final long modelRequestMillis;

        Result(InferenceResult inference, int errorCode, String retryReason) {
            this(inference, errorCode, retryReason, 0L, 0L);
        }

        Result(
                InferenceResult inference,
                int errorCode,
                String retryReason,
                long inputPreparationMillis,
                long modelRequestMillis) {
            this.inference = inference;
            this.errorCode = errorCode;
            this.retryReason = retryReason;
            this.inputPreparationMillis = inputPreparationMillis;
            this.modelRequestMillis = modelRequestMillis;
        }
    }

    static final class AudioResult {
        final VideoTranscript transcript;
        final int errorCode;
        final String retryReason;
        final long modelRequestMillis;
        final long sourceAudioMillis;

        AudioResult(
                VideoTranscript transcript,
                int errorCode,
                String retryReason,
                long modelRequestMillis,
                long sourceAudioMillis) {
            this.transcript = transcript;
            this.errorCode = errorCode;
            this.retryReason = retryReason;
            this.modelRequestMillis = modelRequestMillis;
            this.sourceAudioMillis = sourceAudioMillis;
        }
    }

    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IAiosModelService service;
    private volatile boolean bound;
    private volatile boolean closed;
    private volatile MediaInferenceAttempt<InferenceResult> activeAttempt;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (MediaBrokerClient.this) {
                if (!closed) service = IAiosModelService.Stub.asInterface(binder);
            }
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnectBroker("model_broker_disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnectBroker("model_broker_binding_died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            disconnectBroker("model_broker_null_binding");
        }
    };

    MediaBrokerClient(Context context) {
        this.context = context;
    }

    Result process(MediaJobStore.PendingJob job, MediaConstraintProbe constraints)
            throws IOException, InterruptedException {
        String blocked = constraints.blockedReason();
        if (blocked != null) {
            return new Result(null, ERROR_CONSTRAINT_BLOCKED, blocked);
        }
        long preparationStarted = SystemClock.elapsedRealtime();
        PreparedMedia prepared;
        try {
            prepared = PreparedMedia.open(context, job, constraints);
        } catch (VideoStoryboard.BlockedException error) {
            return new Result(null, ERROR_CONSTRAINT_BLOCKED, error.reason);
        }
        long inputPreparationMillis = MediaTiming.elapsedDuration(
                preparationStarted, SystemClock.elapsedRealtime());
        try (prepared) {
            blocked = constraints.blockedReason();
            if (blocked != null) {
                return new Result(null, ERROR_CONSTRAINT_BLOCKED, blocked);
            }
            if (!ensureConnected()) {
                return new Result(
                        null, ERROR_BROKER_UNAVAILABLE, "model_broker_unavailable");
            }
            blocked = constraints.blockedReason();
            if (blocked != null) {
                return new Result(null, ERROR_CONSTRAINT_BLOCKED, blocked);
            }

            MediaInferenceAttempt<InferenceResult> attempt = beginAttempt();
            IModelCallback callback = new IModelCallback.Stub() {
                @Override
                public void onChunk(GenerationChunk chunk) {
                    // Media results are committed only from the final typed result.
                }

                @Override
                public void onCompleted(InferenceResult value) {
                    attempt.complete(value);
                }

                @Override
                public void onError(int code, String message) {
                    attempt.fail(code, "model_runtime_error");
                }
            };

            ModelRequest request = new ModelRequest();
            request.requestId = "media:" + job.id + ":" + job.generation;
            request.capability = prepared.capability;
            request.workload = "media_background";
            String locale = Locale.getDefault().getLanguage();
            request.language = "es".equals(locale) ? "es" : "en";
            request.maxOutputTokens = 1024;
            request.deadlineElapsedRealtimeMillis =
                    SystemClock.elapsedRealtime()
                            + TimeUnit.MINUTES.toMillis(INFERENCE_TIMEOUT_MINUTES);
            request.allowFallback = true;

            IAiosModelService broker = service;
            if (broker == null) {
                finishAttempt(attempt);
                return new Result(
                        null, ERROR_BROKER_UNAVAILABLE, "model_broker_disconnected");
            }
            long modelRequestStarted = SystemClock.elapsedRealtime();
            try {
                long brokerSessionId = broker.createSession(request, callback);
                if (brokerSessionId <= 0L) {
                    attempt.fail(ERROR_BROKER_UNAVAILABLE, "model_session_rejected");
                } else if (!attempt.attachSession(brokerSessionId)) {
                    cancelSession(broker, brokerSessionId);
                } else if (!attempt.markSubmitted()) {
                    cancelSession(broker, brokerSessionId);
                } else {
                    broker.submitMedia(
                            brokerSessionId,
                            prepared.descriptor,
                            prepared.submittedMimeType,
                            true);
                }
                MediaInferenceAttempt.Snapshot<InferenceResult> terminal = awaitTerminal(
                        attempt,
                        broker,
                        constraints,
                        SystemClock.elapsedRealtime()
                                + TimeUnit.MINUTES.toMillis(INFERENCE_TIMEOUT_MINUTES),
                        "media_inference_timeout");
                return new Result(
                        terminal.result,
                        terminal.errorCode,
                        terminal.result == null ? terminal.reason : null,
                        inputPreparationMillis,
                        MediaTiming.elapsedDuration(
                                modelRequestStarted, SystemClock.elapsedRealtime()));
            } catch (RemoteException error) {
                cancelAttempt(
                        attempt,
                        broker,
                        ERROR_BROKER_UNAVAILABLE,
                        "model_broker_disconnected");
                throw new IOException("Model Broker binder failed", error);
            } catch (RuntimeException error) {
                cancelAttempt(
                        attempt,
                        broker,
                        ERROR_BROKER_UNAVAILABLE,
                        "media_inference_failed");
                throw error;
            } finally {
                finishAttempt(attempt);
            }
        }
    }

    AudioResult transcribeVideoAudio(
            MediaJobStore.PendingJob job,
            MediaConstraintProbe constraints)
            throws IOException, InterruptedException {
        if (!MediaInputPolicy.isVideo(job.mimeType)) {
            return new AudioResult(
                    VideoTranscript.notApplicable(), 0, null, 0L, MediaTiming.UNKNOWN_MILLIS);
        }
        String blocked = constraints.blockedReason();
        if (blocked != null) {
            return new AudioResult(
                    null, ERROR_CONSTRAINT_BLOCKED, blocked, 0L, MediaTiming.UNKNOWN_MILLIS);
        }
        if (!ensureConnected()) {
            return new AudioResult(
                    null,
                    ERROR_BROKER_UNAVAILABLE,
                    "model_broker_unavailable",
                    0L,
                    MediaTiming.UNKNOWN_MILLIS);
        }

        MediaInferenceAttempt<InferenceResult> attempt = beginAttempt();
        Holder holder = new Holder();
        IModelCallback callback = new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (chunk == null || !chunk.isFinal || !attempt.isActive()) return;
                synchronized (holder.chunks) {
                    int length = chunk.text == null ? 0 : chunk.text.length();
                    if (holder.chunks.size() >= VideoTranscript.MAX_SEGMENTS
                            || length > VideoTranscript.MAX_SEGMENT_CHARS
                            || holder.chunkChars > VideoTranscript.MAX_TRANSCRIPT_CHARS - length) {
                        holder.invalidChunks = true;
                    } else {
                        holder.chunks.add(chunk);
                        holder.chunkChars += length;
                    }
                }
            }

            @Override
            public void onCompleted(InferenceResult value) {
                attempt.complete(value);
            }

            @Override
            public void onError(int code, String message) {
                attempt.fail(code, "video_asr_runtime_error");
            }
        };

        ModelRequest request = new ModelRequest();
        request.requestId = "video-asr:" + job.id + ":" + job.generation;
        request.capability = "streaming_asr";
        request.workload = "media_background";
        request.language = "und";
        request.maxOutputTokens = 0;
        // Full-audio ASR is bounded by pipe EOF, this worker's timeout/constraints,
        // Binder death, and broker preemption rather than an arbitrary clip duration.
        request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE;
        request.allowFallback = true;

        ParcelFileDescriptor[] pipe = null;
        long started = SystemClock.elapsedRealtime();
        VideoAudioExtractor.Result extraction;
        IAiosModelService broker = service;
        if (broker == null) {
            finishAttempt(attempt);
            return new AudioResult(
                    null,
                    ERROR_BROKER_UNAVAILABLE,
                    "model_broker_disconnected",
                    0L,
                    MediaTiming.UNKNOWN_MILLIS);
        }
        try {
            pipe = ParcelFileDescriptor.createPipe();
            long brokerSessionId = broker.createSession(request, callback);
            if (brokerSessionId <= 0L) {
                attempt.fail(ERROR_BROKER_UNAVAILABLE, "video_asr_session_rejected");
                closePipe(pipe);
                MediaInferenceAttempt.Snapshot<InferenceResult> terminal = attempt.snapshot();
                finishAttempt(attempt);
                return new AudioResult(
                        null,
                        terminal.errorCode,
                        terminal.reason,
                        0L,
                        MediaTiming.UNKNOWN_MILLIS);
            }
            if (!attempt.attachSession(brokerSessionId)) {
                cancelSession(broker, brokerSessionId);
                closePipe(pipe);
                MediaInferenceAttempt.Snapshot<InferenceResult> terminal = attempt.snapshot();
                finishAttempt(attempt);
                return new AudioResult(
                        null,
                        terminal.errorCode,
                        terminal.reason == null
                                ? "video_asr_completed_before_input" : terminal.reason,
                        0L,
                        MediaTiming.UNKNOWN_MILLIS);
            }
            AudioStreamFormat format = new AudioStreamFormat();
            format.sampleRateHz = VideoAudioExtractor.OUTPUT_SAMPLE_RATE_HZ;
            format.channelCount = 1;
            format.pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            format.direction = "media";
            if (attempt.markSubmitted()) {
                broker.submitAudio(brokerSessionId, pipe[0], format, false);
            } else {
                cancelSession(broker, brokerSessionId);
            }
            if (!attempt.isActive()) {
                closePipe(pipe);
                MediaInferenceAttempt.Snapshot<InferenceResult> terminal = attempt.snapshot();
                finishAttempt(attempt);
                return new AudioResult(
                        null,
                        terminal.errorCode,
                        terminal.reason == null
                                ? "video_asr_ended_before_audio" : terminal.reason,
                        0L,
                        MediaTiming.UNKNOWN_MILLIS);
            }
            pipe[0].close();
            pipe[0] = null;
            try (OutputStream sink =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                pipe[1] = null;
                extraction = VideoAudioExtractor.stream(
                        context, Uri.parse(job.uri), sink, constraints);
            }
        } catch (VideoStoryboard.BlockedException error) {
            cancelAttempt(attempt, broker, ERROR_CONSTRAINT_BLOCKED, error.reason);
            closePipe(pipe);
            finishAttempt(attempt);
            return new AudioResult(
                    null,
                    ERROR_CONSTRAINT_BLOCKED,
                    error.reason,
                    0L,
                    MediaTiming.UNKNOWN_MILLIS);
        } catch (RemoteException error) {
            cancelAttempt(
                    attempt,
                    broker,
                    ERROR_BROKER_UNAVAILABLE,
                    "model_broker_disconnected");
            closePipe(pipe);
            finishAttempt(attempt);
            throw new IOException("Model Broker video ASR binder failed", error);
        } catch (IOException | InterruptedException | RuntimeException error) {
            cancelAttempt(attempt, broker, ERROR_BROKER_UNAVAILABLE,
                    "video_asr_input_failed");
            closePipe(pipe);
            finishAttempt(attempt);
            throw error;
        }

        MediaInferenceAttempt.Snapshot<InferenceResult> terminal;
        try {
            terminal = awaitTerminal(
                    attempt,
                    broker,
                    constraints,
                    SystemClock.elapsedRealtime()
                            + TimeUnit.MINUTES.toMillis(INFERENCE_TIMEOUT_MINUTES),
                    "video_asr_timeout");
        } finally {
            finishAttempt(attempt);
        }
        if (terminal.result == null) {
            return new AudioResult(
                    null,
                    terminal.errorCode,
                    terminal.reason,
                    MediaTiming.elapsedDuration(started, SystemClock.elapsedRealtime()),
                    extraction.decodedDurationMillis);
        }
        if (!extraction.hasAudio) {
            return new AudioResult(
                    VideoTranscript.noAudio(),
                    0,
                    null,
                    MediaTiming.elapsedDuration(started, SystemClock.elapsedRealtime()),
                    extraction.decodedDurationMillis);
        }
        List<GenerationChunk> chunks;
        synchronized (holder.chunks) {
            chunks = List.copyOf(holder.chunks);
            if (holder.invalidChunks) {
                throw new VideoStoryboard.InvalidVideoException(
                        "video ASR exceeded the private subtitle bound");
            }
        }
        try {
            return new AudioResult(
                    VideoTranscript.fromInference(
                            terminal.result, chunks, extraction.timelineOffsetMillis),
                    0,
                    null,
                    MediaTiming.elapsedDuration(started, SystemClock.elapsedRealtime()),
                    extraction.decodedDurationMillis);
        } catch (IllegalArgumentException error) {
            throw new VideoStoryboard.InvalidVideoException(
                    "video ASR returned invalid timestamped subtitles");
        }
    }

    private boolean ensureConnected() throws InterruptedException {
        boolean shouldBind;
        synchronized (this) {
            if (closed) return false;
            if (service != null) return true;
            shouldBind = !bound;
            if (shouldBind) bound = true;
        }
        if (shouldBind) {
            Intent intent = new Intent("com.aios.model.MODEL_SERVICE")
                    .setPackage("com.aios.modelbroker");
            boolean didBind = false;
            try {
                didBind = context.bindService(
                        intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                Log.w(TAG, "cannot bind Model Broker", error);
            }
            boolean release;
            synchronized (this) {
                release = closed && didBind;
                if (!didBind || closed) bound = false;
            }
            if (release) unbindQuietly();
            if (!didBind) connected.countDown();
        }
        return !closed && bound && connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                && service != null;
    }

    private synchronized MediaInferenceAttempt<InferenceResult> beginAttempt() {
        if (closed) throw new IllegalStateException("media Broker client is closed");
        if (activeAttempt != null) throw new IllegalStateException("media attempt overlaps");
        MediaInferenceAttempt<InferenceResult> attempt = new MediaInferenceAttempt<>();
        activeAttempt = attempt;
        return attempt;
    }

    private synchronized void finishAttempt(
            MediaInferenceAttempt<InferenceResult> attempt) {
        if (activeAttempt == attempt) activeAttempt = null;
    }

    private MediaInferenceAttempt.Snapshot<InferenceResult> awaitTerminal(
            MediaInferenceAttempt<InferenceResult> attempt,
            IAiosModelService broker,
            MediaConstraintProbe constraints,
            long timeoutAt,
            String timeoutReason) throws InterruptedException {
        while (attempt.isActive()) {
            String blocked = constraints.blockedReason();
            if (blocked != null) {
                cancelAttempt(attempt, broker, ERROR_CONSTRAINT_BLOCKED, blocked);
                break;
            }
            long remaining = timeoutAt - SystemClock.elapsedRealtime();
            if (remaining <= 0L) {
                cancelAttempt(
                        attempt, broker, ERROR_INFERENCE_TIMEOUT, timeoutReason);
                break;
            }
            try {
                attempt.await(
                        Math.min(CONSTRAINT_RECHECK_MILLIS, remaining),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                cancelAttempt(
                        attempt,
                        broker,
                        ERROR_CONSTRAINT_BLOCKED,
                        "media_worker_interrupted");
                throw error;
            }
        }
        return attempt.snapshot();
    }

    private void disconnectBroker(String reason) {
        MediaInferenceAttempt<InferenceResult> attempt;
        synchronized (this) {
            service = null;
            attempt = activeAttempt;
        }
        connected.countDown();
        if (attempt != null) attempt.fail(ERROR_BROKER_UNAVAILABLE, reason);
    }

    private static void cancelAttempt(
            MediaInferenceAttempt<InferenceResult> attempt,
            IAiosModelService broker,
            int errorCode,
            String reason) {
        long ownedSession = attempt.cancel(errorCode, reason);
        cancelSession(broker, ownedSession);
    }

    private static void cancelSession(IAiosModelService broker, long session) {
        if (broker == null || session <= 0L) return;
        try {
            broker.cancel(session);
        } catch (RemoteException | RuntimeException ignored) {
            // Broker death already releases its runtime session.
        }
    }

    private static final class PreparedMedia implements AutoCloseable {
        final String capability;
        final String submittedMimeType;
        final ParcelFileDescriptor descriptor;
        final File temporary;

        private PreparedMedia(
                String capability,
                String submittedMimeType,
                ParcelFileDescriptor descriptor,
                File temporary) {
            this.capability = capability;
            this.submittedMimeType = submittedMimeType;
            this.descriptor = descriptor;
            this.temporary = temporary;
        }

        static PreparedMedia open(
                Context context,
                MediaJobStore.PendingJob job,
                MediaConstraintProbe constraints) throws IOException, InterruptedException {
            String capability = MediaInputPolicy.capability(job.mimeType);
            String submittedMimeType = MediaInputPolicy.submittedMimeType(job.mimeType);
            if (capability == null || submittedMimeType == null) {
                throw new VideoStoryboard.InvalidVideoException(
                        "queued media type is unsupported");
            }
            Uri uri = Uri.parse(job.uri);
            if (MediaInputPolicy.isImage(job.mimeType)) {
                ParcelFileDescriptor descriptor =
                        context.getContentResolver().openFileDescriptor(uri, "r");
                if (descriptor == null) {
                    throw new FileNotFoundException("cannot open queued image");
                }
                return new PreparedMedia(
                        capability, submittedMimeType, descriptor, null);
            }
            File storyboard = VideoStoryboard.create(context, uri, constraints);
            try {
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        storyboard, ParcelFileDescriptor.MODE_READ_ONLY);
                return new PreparedMedia(
                        capability, submittedMimeType, descriptor, storyboard);
            } catch (IOException | RuntimeException error) {
                storyboard.delete();
                throw error;
            }
        }

        @Override
        public void close() {
            try {
                descriptor.close();
            } catch (IOException error) {
                Log.w(TAG, "cannot close prepared media descriptor", error);
            }
            if (VideoStoryboard.isStoryboard(temporary)
                    && temporary.exists()
                    && !temporary.delete()) {
                Log.w(TAG, "cannot erase temporary video storyboard");
            }
        }
    }

    @Override
    public void close() {
        IAiosModelService current;
        MediaInferenceAttempt<InferenceResult> attempt;
        boolean unbind;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = service;
            service = null;
            attempt = activeAttempt;
            activeAttempt = null;
            unbind = bound;
            bound = false;
        }
        connected.countDown();
        if (attempt != null) {
            cancelAttempt(
                    attempt,
                    current,
                    ERROR_CONSTRAINT_BLOCKED,
                    "media_client_closed");
        }
        if (unbind) unbindQuietly();
    }

    private void unbindQuietly() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // onStopJob can race bindService completion.
        }
    }

    private static final class Holder {
        final List<GenerationChunk> chunks = new ArrayList<>();
        int chunkChars;
        boolean invalidChunks;
    }

    private static void closePipe(ParcelFileDescriptor[] pipe) {
        if (pipe == null) return;
        for (ParcelFileDescriptor descriptor : pipe) {
            if (descriptor == null) continue;
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // Best effort after stream setup or cancellation failure.
            }
        }
    }
}
