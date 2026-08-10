package com.aios.mediaintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
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

    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IAiosModelService service;
    private boolean bound;
    private volatile long sessionId = -1L;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IAiosModelService.Stub.asInterface(binder);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
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
            Intent intent = new Intent("com.aios.model.MODEL_SERVICE")
                    .setPackage("com.aios.modelbroker");
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    || service == null) {
                return new Result(
                        null, ERROR_BROKER_UNAVAILABLE, "model_broker_unavailable");
            }
            blocked = constraints.blockedReason();
            if (blocked != null) {
                return new Result(null, ERROR_CONSTRAINT_BLOCKED, blocked);
            }

            CountDownLatch completed = new CountDownLatch(1);
            Holder holder = new Holder();
            IModelCallback callback = new IModelCallback.Stub() {
                @Override
                public void onChunk(GenerationChunk chunk) {
                    // Media results are committed only from the final typed result.
                }

                @Override
                public void onCompleted(InferenceResult value) {
                    holder.result = value;
                    completed.countDown();
                }

                @Override
                public void onError(int code, String message) {
                    holder.errorCode = code;
                    completed.countDown();
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
                    SystemClock.elapsedRealtime() + 30_000L;
            request.allowFallback = true;

            try {
                long modelRequestStarted = SystemClock.elapsedRealtime();
                sessionId = service.createSession(request, callback);
                if (sessionId <= 0L) {
                    return new Result(
                            null,
                            holder.errorCode == 0
                                    ? ERROR_BROKER_UNAVAILABLE : holder.errorCode,
                            "model_session_rejected");
                }
                service.submitMedia(
                        sessionId,
                        prepared.descriptor,
                        prepared.submittedMimeType,
                        true);
                long timeoutAt = SystemClock.elapsedRealtime()
                        + TimeUnit.MINUTES.toMillis(INFERENCE_TIMEOUT_MINUTES);
                while (completed.getCount() != 0L) {
                    blocked = constraints.blockedReason();
                    if (blocked != null) {
                        cancelActiveSession();
                        return new Result(null, ERROR_CONSTRAINT_BLOCKED, blocked);
                    }
                    long remaining = timeoutAt - SystemClock.elapsedRealtime();
                    if (remaining <= 0L) {
                        cancelActiveSession();
                        return new Result(
                                null, ERROR_INFERENCE_TIMEOUT, "media_inference_timeout");
                    }
                    completed.await(
                            Math.min(CONSTRAINT_RECHECK_MILLIS, remaining),
                            TimeUnit.MILLISECONDS);
                }
                return new Result(
                        holder.result,
                        holder.errorCode,
                        holder.result == null ? "model_runtime_error" : null,
                        inputPreparationMillis,
                        MediaTiming.elapsedDuration(
                                modelRequestStarted, SystemClock.elapsedRealtime()));
            } catch (RemoteException error) {
                throw new IOException("Model Broker binder failed", error);
            }
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

    private void cancelActiveSession() {
        IAiosModelService current = service;
        long activeSession = sessionId;
        sessionId = -1L;
        if (current != null && activeSession > 0L) {
            try {
                current.cancel(activeSession);
            } catch (RemoteException ignored) {
                // Binder death already cancels the server session.
            }
        }
    }

    @Override
    public void close() {
        cancelActiveSession();
        service = null;
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    private static final class Holder {
        volatile InferenceResult result;
        volatile int errorCode;
    }
}
