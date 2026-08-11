package com.aios.callintelligence;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.IdentityHashMap;
import java.util.Map;
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

        void onAsrReady(Object brokerIdentity);

        void onAsrUnavailable(Object brokerIdentity);
    }

    static final class Stream implements AutoCloseable {
        private final AsrBrokerClient owner;
        final OutputStream sink;
        final Object identity;
        final Object brokerIdentity;
        private boolean closed;

        Stream(
                AsrBrokerClient owner,
                OutputStream sink,
                Object identity,
                Object brokerIdentity) {
            this.owner = owner;
            this.sink = sink;
            this.identity = identity;
            this.brokerIdentity = brokerIdentity;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.forget(this);
            try {
                sink.close();
            } catch (IOException ignored) {
                // Closing the pipe is the end-of-stream signal.
            }
        }
    }

    private final Listener listener;
    private final ResilientModelBrokerBinding binding;
    private final IBinder callActivityToken = new Binder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "aios-asr-capabilities");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private IAiosModelService service;
    private final Map<Object, Object> activeStreams = new IdentityHashMap<>();
    private boolean available;
    private boolean callActive;
    private boolean closed;
    private Object brokerIdentity;
    private long nextStreamGeneration;

    AsrBrokerClient(Context context, Listener listener) {
        this.listener = listener;
        binding = new ResilientModelBrokerBinding(
                context,
                new ResilientModelBrokerBinding.Listener() {
                    @Override
                    public void onConnected(IAiosModelService candidate) {
                        worker.execute(() -> loadCapabilities(candidate));
                    }

                    @Override
                    public void onDisconnected() {
                        clearService();
                    }
                });
    }

    void start() {
        binding.start();
    }

    synchronized void setCallActive(boolean active) {
        callActive = active;
        applyCallStateLocked();
    }

    synchronized boolean isAvailable() {
        return service != null && available;
    }

    synchronized boolean acceptsCallback(Object streamIdentity) {
        return streamIdentity != null && brokerIdentity != null
                && activeStreams.get(streamIdentity) == brokerIdentity;
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
        Object currentBrokerIdentity = brokerIdentity;
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
            // Call ASR follows the Telecom/capture lifecycle rather than a short inference turn.
            request.deadlineElapsedRealtimeMillis = Long.MAX_VALUE;
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
            Stream stream = new Stream(
                    this, sink, streamIdentity, currentBrokerIdentity);
            if (service != current || brokerIdentity != currentBrokerIdentity) {
                stream.close();
                return null;
            }
            activeStreams.put(streamIdentity, currentBrokerIdentity);
            return stream;
        } catch (RemoteException error) {
            closePipe(pipe);
            binding.invalidate(current);
            listener.onAsrStatus(
                    callId, direction, streamIdentity, "asr_stream_unavailable");
            return null;
        } catch (IOException | RuntimeException error) {
            closePipe(pipe);
            listener.onAsrStatus(
                    callId, direction, streamIdentity, "asr_stream_unavailable");
            return null;
        }
    }

    @Override
    public void close() {
        IAiosModelService current;
        boolean wasCallActive;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = service;
            wasCallActive = callActive;
            service = null;
            available = false;
            brokerIdentity = null;
            activeStreams.clear();
            callActive = false;
        }
        if (current != null && wasCallActive) {
            try {
                current.setCallActive(callActivityToken, false);
            } catch (RemoteException ignored) {
                // Service death already clears process-local state.
            }
        }
        binding.close();
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
            binding.invalidate(candidate);
            return;
        }
        Object readyIdentity = new Object();
        boolean ready;
        synchronized (this) {
            if (closed || !binding.isCurrent(candidate)) return;
            service = candidate;
            available = found;
            brokerIdentity = found ? readyIdentity : null;
            applyCallStateLocked();
            ready = service == candidate && available;
        }
        binding.markReady(candidate);
        if (ready) listener.onAsrReady(readyIdentity);
    }

    private void clearService() {
        Object lostIdentity;
        synchronized (this) {
            lostIdentity = brokerIdentity;
            service = null;
            available = false;
            brokerIdentity = null;
            activeStreams.clear();
        }
        if (lostIdentity != null) listener.onAsrUnavailable(lostIdentity);
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
            IAiosModelService failed = service;
            Object lostIdentity = brokerIdentity;
            service = null;
            available = false;
            brokerIdentity = null;
            activeStreams.clear();
            binding.invalidate(failed);
            if (lostIdentity != null) listener.onAsrUnavailable(lostIdentity);
        }
    }

    private synchronized void forget(Stream stream) {
        activeStreams.remove(stream.identity);
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
