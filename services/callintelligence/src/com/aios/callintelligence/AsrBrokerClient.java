package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent connection that turns PCM pipes into streaming ASR callbacks. */
final class AsrBrokerClient implements AutoCloseable {
    interface Listener {
        void onTranscript(
                String callId,
                String direction,
                Object streamIdentity,
                String language,
                GenerationChunk chunk);

        void onAsrStatus(
                String callId, String direction, Object streamIdentity, String detail);
    }

    static final class Stream implements AutoCloseable {
        final OutputStream sink;
        final Object identity;

        Stream(OutputStream sink, Object identity) {
            this.sink = sink;
            this.identity = identity;
        }

        @Override
        public void close() {
            try {
                sink.close();
            } catch (IOException ignored) {
                // Closing the pipe is the end-of-stream signal.
            }
        }
    }

    private final Context context;
    private final Listener listener;
    private final IBinder callActivityToken = new Binder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "aios-asr-capabilities");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private IAiosModelService service;
    private boolean available;
    private boolean bound;
    private boolean callActive;
    private boolean closed;
    private long nextStreamGeneration;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosModelService candidate = IAiosModelService.Stub.asInterface(binder);
            worker.execute(() -> loadCapabilities(candidate));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (AsrBrokerClient.this) {
                service = null;
                available = false;
            }
        }
    };

    AsrBrokerClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    synchronized void start() {
        if (closed || bound) {
            return;
        }
        Intent intent = new Intent("com.aios.model.MODEL_SERVICE")
                .setPackage("com.aios.modelbroker");
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    synchronized void setCallActive(boolean active) {
        callActive = active;
        applyCallStateLocked();
    }

    synchronized boolean isAvailable() {
        return service != null && available;
    }

    synchronized Stream openStream(String callId, String direction) {
        Object streamIdentity = new Object();
        if (nextStreamGeneration == Long.MAX_VALUE) {
            listener.onAsrStatus(
                    callId, direction, streamIdentity, "asr_stream_generation_exhausted");
            return null;
        }
        long streamGeneration = ++nextStreamGeneration;
        IAiosModelService current = service;
        if (current == null || !available) {
            listener.onAsrStatus(
                    callId, direction, streamIdentity, "model_broker_unavailable");
            return null;
        }
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            ModelRequest request = new ModelRequest();
            request.requestId = callId + ":" + direction + ":" + streamGeneration;
            request.capability = "streaming_asr";
            request.workload = "downlink".equals(direction) ? "call_rx" : "call_tx";
            request.language = "und";
            request.maxOutputTokens = 0;
            request.deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000L;
            request.allowFallback = true;
            IModelCallback callback = callback(callId, direction, streamIdentity);
            long sessionId = current.createSession(request, callback);
            if (sessionId <= 0L) {
                closePipe(pipe);
                return null;
            }
            AudioStreamFormat format = new AudioStreamFormat();
            format.sampleRateHz = 16_000;
            format.channelCount = 1;
            format.pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            format.direction = direction;
            current.submitAudio(sessionId, pipe[0], format, false);
            pipe[0].close();
            OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            return new Stream(sink, streamIdentity);
        } catch (IOException | RemoteException | RuntimeException error) {
            closePipe(pipe);
            listener.onAsrStatus(
                    callId, direction, streamIdentity, "asr_stream_unavailable");
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (service != null && callActive) {
            try {
                service.setCallActive(callActivityToken, false);
            } catch (RemoteException ignored) {
                // Service death already clears process-local state.
            }
        }
        service = null;
        available = false;
        callActive = false;
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
        worker.shutdownNow();
    }

    private void loadCapabilities(IAiosModelService candidate) {
        boolean found = false;
        try {
            for (ModelCapability capability : candidate.listCapabilities()) {
                if (capability != null && "streaming_asr".equals(capability.capability)
                        && capability.available && capability.languages != null) {
                    for (String language : capability.languages) {
                        if ("und".equals(language)) found = true;
                    }
                }
            }
        } catch (RemoteException | RuntimeException error) {
            candidate = null;
        }
        synchronized (this) {
            if (closed) return;
            service = candidate;
            available = candidate != null && found;
            applyCallStateLocked();
        }
    }

    private IModelCallback callback(
            String callId, String direction, Object streamIdentity) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (chunk != null) {
                    listener.onTranscript(
                            callId, direction, streamIdentity, chunk.language, chunk);
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
                listener.onAsrStatus(callId, direction, streamIdentity, "asr_complete");
            }

            @Override
            public void onError(int code, String message) {
                listener.onAsrStatus(
                        callId, direction, streamIdentity, "asr_error_" + code);
            }
        };
    }

    private void applyCallStateLocked() {
        if (service == null) {
            return;
        }
        try {
            service.setCallActive(callActivityToken, callActive);
        } catch (RemoteException ignored) {
            service = null;
        }
    }

    private static void closePipe(ParcelFileDescriptor[] pipe) {
        if (pipe == null) {
            return;
        }
        for (ParcelFileDescriptor descriptor : pipe) {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // Best effort after setup failure.
                }
            }
        }
    }
}
