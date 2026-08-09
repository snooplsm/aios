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

import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One bounded image-understanding request for a JobService worker thread. */
final class MediaBrokerClient implements AutoCloseable {
    private static final long BIND_TIMEOUT_SECONDS = 5L;
    private static final long INFERENCE_TIMEOUT_MINUTES = 2L;

    static final class Result {
        final InferenceResult inference;
        final int errorCode;

        Result(InferenceResult inference, int errorCode) {
            this.inference = inference;
            this.errorCode = errorCode;
        }
    }

    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private IAiosModelService service;
    private boolean bound;
    private long sessionId = -1L;

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

    Result process(MediaJobStore.PendingJob job) throws IOException, InterruptedException {
        Intent intent = new Intent("com.aios.model.MODEL_SERVICE")
                .setPackage("com.aios.modelbroker");
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound || !connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                || service == null) {
            return new Result(null, 1);
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
        request.capability = "image_understanding";
        request.workload = "media_background";
        String locale = Locale.getDefault().getLanguage();
        request.language = "es".equals(locale) ? "es" : "en";
        request.maxOutputTokens = 1024;
        request.deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000L;
        request.allowFallback = true;

        try {
            sessionId = service.createSession(request, callback);
            if (sessionId <= 0L) {
                return new Result(null, holder.errorCode == 0 ? 1 : holder.errorCode);
            }
            Uri uri = Uri.parse(job.uri);
            try (ParcelFileDescriptor media =
                         context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (media == null) {
                    throw new IOException("cannot open queued media");
                }
                service.submitMedia(sessionId, media, job.mimeType, true);
            }
            if (!completed.await(INFERENCE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                return new Result(null, 2);
            }
            return new Result(holder.result, holder.errorCode);
        } catch (RemoteException error) {
            throw new IOException("Model Broker binder failed", error);
        }
    }

    @Override
    public void close() {
        IAiosModelService current = service;
        if (current != null && sessionId > 0L) {
            try {
                current.cancel(sessionId);
            } catch (RemoteException ignored) {
                // Binder death already cancels the server session.
            }
        }
        sessionId = -1L;
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
