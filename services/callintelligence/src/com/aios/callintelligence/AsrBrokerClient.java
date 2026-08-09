package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;

import java.io.IOException;
import java.io.OutputStream;

/** Persistent connection that turns PCM pipes into streaming ASR callbacks. */
final class AsrBrokerClient implements AutoCloseable {
    interface Listener {
        void onTranscript(
                String callId,
                String direction,
                String language,
                GenerationChunk chunk);

        void onAsrStatus(String callId, String direction, String detail);
    }

    static final class Stream implements AutoCloseable {
        final OutputStream sink;

        Stream(OutputStream sink) {
            this.sink = sink;
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
    private IAiosModelService service;
    private boolean bound;
    private boolean callActive;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (AsrBrokerClient.this) {
                service = IAiosModelService.Stub.asInterface(binder);
                applyCallStateLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (AsrBrokerClient.this) {
                service = null;
            }
        }
    };

    AsrBrokerClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    synchronized void start() {
        if (bound) {
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

    synchronized Stream openStream(String callId, String direction) {
        IAiosModelService current = service;
        if (current == null) {
            listener.onAsrStatus(callId, direction, "model_broker_unavailable");
            return null;
        }
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            ModelRequest request = new ModelRequest();
            request.requestId = callId + ":" + direction;
            request.capability = "streaming_asr";
            request.workload = "downlink".equals(direction) ? "call_rx" : "call_tx";
            request.language = "und";
            request.maxOutputTokens = 0;
            request.deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + 30_000L;
            request.allowFallback = true;
            IModelCallback callback = callback(callId, direction);
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
            return new Stream(sink);
        } catch (IOException | RemoteException | RuntimeException error) {
            closePipe(pipe);
            listener.onAsrStatus(callId, direction, "asr_stream_unavailable");
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (service != null && callActive) {
            try {
                service.setCallActive(false);
            } catch (RemoteException ignored) {
                // Service death already clears process-local state.
            }
        }
        service = null;
        callActive = false;
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    private IModelCallback callback(String callId, String direction) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (chunk != null) {
                    listener.onTranscript(callId, direction, chunk.language, chunk);
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
                listener.onAsrStatus(callId, direction, "asr_complete");
            }

            @Override
            public void onError(int code, String message) {
                listener.onAsrStatus(callId, direction, "asr_error_" + code);
            }
        };
    }

    private void applyCallStateLocked() {
        if (service == null) {
            return;
        }
        try {
            service.setCallActive(callActive);
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
