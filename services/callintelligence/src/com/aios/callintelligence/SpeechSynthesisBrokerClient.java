package com.aios.callintelligence;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Requests bounded, on-device synthesized PCM from Model Broker. */
final class SpeechSynthesisBrokerClient implements AutoCloseable {
    private static final int OUTPUT_SAMPLE_RATE_HZ = 44_100;
    private static final long REQUEST_DEADLINE_MILLIS = 30_000L;
    private static final int MAX_TEXT_CHARS = 2_048;

    interface Listener {
        void onStatus(String callId, String requestId, Speech speech, String detail);
    }

    private final Listener listener;
    private final ResilientModelBrokerBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "aios-speech-broker");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final Set<Speech> active = new HashSet<>();
    private final Set<String> languages = new HashSet<>();
    private IAiosModelService service;
    private boolean available;
    private boolean closed;

    SpeechSynthesisBrokerClient(Context context, Listener listener) {
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

    synchronized boolean isAvailable(String language) {
        return !closed && service != null && available && languages.contains(language);
    }

    Speech prepare(
            String callId, String requestId, String language, String text) throws IOException {
        IAiosModelService broker;
        synchronized (this) {
            if (closed || !available || service == null || !languages.contains(language)) {
                throw new IOException("speech synthesis is unavailable");
            }
            broker = service;
        }
        if (callId == null || callId.isEmpty() || requestId == null || requestId.isEmpty()
                || text == null || text.isBlank()
                || text.length() > MAX_TEXT_CHARS) {
            throw new IOException("invalid speech synthesis request");
        }

        ParcelFileDescriptor[] pipe = null;
        Speech speech = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
            ModelRequest request = new ModelRequest();
            request.requestId = requestId;
            request.capability = "speech_synthesis";
            request.workload = "call_agent";
            request.language = language;
            request.maxOutputTokens = 0;
            request.deadlineElapsedRealtimeMillis =
                    SystemClock.elapsedRealtime() + REQUEST_DEADLINE_MILLIS;
            request.allowFallback = false;
            speech = new Speech(callId, requestId, broker, pipe[0], text);
            pipe[0] = null;
            long sessionId = broker.createSession(request, callback(speech));
            if (sessionId <= 0L) {
                throw new IOException("speech synthesis session was rejected");
            }
            speech.assignSessionId(sessionId);
            if (speech.isFinished()) {
                throw new IOException("speech synthesis terminated during setup");
            }
            synchronized (this) {
                if (closed || service != broker) {
                    throw new IOException("model broker disconnected during setup");
                }
                active.add(speech);
            }
            AudioStreamFormat format = new AudioStreamFormat();
            format.sampleRateHz = OUTPUT_SAMPLE_RATE_HZ;
            format.channelCount = 1;
            format.pcmEncoding = 2; // AudioFormat.ENCODING_PCM_16BIT without framework coupling.
            format.direction = "synthesis";
            broker.attachAudioOutput(sessionId, pipe[1], format);
            closeDescriptor(pipe[1]);
            pipe[1] = null;
            if (speech.isFinished()) {
                throw new IOException("speech synthesis terminated during setup");
            }
            return speech;
        } catch (IOException error) {
            if (speech != null) speech.close();
            throw error;
        } catch (RemoteException | RuntimeException error) {
            if (speech != null) speech.close();
            if (error instanceof RemoteException) binding.invalidate(broker);
            throw new IOException("speech synthesis setup failed", error);
        } finally {
            closePipe(pipe);
        }
    }

    @Override
    public void close() {
        ArrayList<Speech> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            available = false;
            languages.clear();
            service = null;
            snapshot = new ArrayList<>(active);
            active.clear();
        }
        for (Speech speech : snapshot) speech.close();
        binding.close();
        worker.shutdownNow();
    }

    private void loadCapabilities(IAiosModelService candidate) {
        boolean found = false;
        Set<String> supported = new HashSet<>();
        try {
            for (ModelCapability capability : candidate.listCapabilities()) {
                if (capability != null && "speech_synthesis".equals(capability.capability)
                        && capability.available && capability.languages != null) {
                    found = true;
                    for (String language : capability.languages) supported.add(language);
                }
            }
        } catch (RemoteException | RuntimeException error) {
            binding.invalidate(candidate);
            return;
        }
        synchronized (this) {
            if (closed || !binding.isCurrent(candidate)) return;
            service = candidate;
            available = found;
            languages.clear();
            if (available) languages.addAll(supported);
        }
        binding.markReady(candidate);
        notifyStatus(null, "availability", null, available
                ? "speech_synthesis_ready" : "speech_synthesis_unavailable");
    }

    private void clearService() {
        ArrayList<Speech> snapshot;
        synchronized (this) {
            service = null;
            available = false;
            languages.clear();
            snapshot = new ArrayList<>(active);
            active.clear();
        }
        for (Speech speech : snapshot) {
            if (speech.claimTerminal()) {
                notifyStatus(
                        speech.callId,
                        speech.requestId,
                        speech,
                        "speech_synthesis_broker_disconnected");
            }
            speech.close();
        }
    }

    private IModelCallback callback(Speech speech) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                // Speech audio is transported only through the bounded PCM pipe.
            }

            @Override
            public void onCompleted(InferenceResult result) {
                if (speech.claimTerminal()) {
                    notifyStatus(
                            speech.callId,
                            speech.requestId,
                            speech,
                            "speech_synthesis_complete");
                }
            }

            @Override
            public void onError(int code, String message) {
                if (speech.claimTerminal()) {
                    notifyStatus(
                            speech.callId,
                            speech.requestId,
                            speech,
                            "speech_synthesis_error_" + code);
                }
            }
        };
    }

    private void notifyStatus(
            String callId, String requestId, Speech speech, String detail) {
        try {
            listener.onStatus(callId, requestId, speech, detail);
        } catch (RuntimeException ignored) {
            // Status reporting cannot own the synthesis lifecycle.
        }
    }

    final class Speech implements AutoCloseable {
        final String callId;
        final String requestId;
        final int sampleRateHz = OUTPUT_SAMPLE_RATE_HZ;
        private final IAiosModelService broker;
        private final String text;
        private final SpeechTerminalGate terminal = new SpeechTerminalGate();
        private long sessionId = -1L;
        private ParcelFileDescriptor pcmInput;
        private boolean started;
        private boolean closed;

        Speech(
                String callId,
                String requestId,
                IAiosModelService broker,
                ParcelFileDescriptor pcmInput,
                String text) {
            this.callId = callId;
            this.requestId = requestId;
            this.broker = broker;
            this.pcmInput = pcmInput;
            this.text = text;
        }

        synchronized void assignSessionId(long value) throws IOException {
            if (value <= 0L || sessionId > 0L || closed) {
                throw new IOException("invalid speech synthesis session lifecycle");
            }
            sessionId = value;
        }

        void start() throws IOException {
            long currentSessionId;
            synchronized (this) {
                if (closed || terminal.isTerminal() || started || sessionId <= 0L) {
                    throw new IOException("speech synthesis cannot start");
                }
                started = true;
                currentSessionId = sessionId;
            }
            try {
                broker.submitText(currentSessionId, text, true);
            } catch (RemoteException error) {
                binding.invalidate(broker);
                throw new IOException("speech synthesis start failed", error);
            } catch (RuntimeException error) {
                throw new IOException("speech synthesis start failed", error);
            }
        }

        synchronized boolean isFinished() {
            return terminal.isTerminal();
        }

        synchronized ParcelFileDescriptor takePcmInput() throws IOException {
            if (closed || terminal.isTerminal() || pcmInput == null) {
                throw new IOException("synthesis PCM input is unavailable");
            }
            ParcelFileDescriptor result = pcmInput;
            pcmInput = null;
            return result;
        }

        synchronized boolean claimTerminal() {
            if (closed || !terminal.claim()) return false;
            synchronized (SpeechSynthesisBrokerClient.this) {
                active.remove(this);
            }
            return true;
        }

        @Override
        public void close() {
            ParcelFileDescriptor descriptor;
            boolean shouldCancel;
            long currentSessionId;
            synchronized (this) {
                if (closed) return;
                closed = true;
                descriptor = pcmInput;
                pcmInput = null;
                currentSessionId = sessionId;
                shouldCancel = terminal.claim() && currentSessionId > 0L;
            }
            synchronized (SpeechSynthesisBrokerClient.this) {
                active.remove(this);
            }
            closeDescriptor(descriptor);
            if (shouldCancel) {
                try {
                    broker.cancel(currentSessionId);
                } catch (RemoteException | RuntimeException ignored) {
                    // Broker death already released the runtime lease.
                }
            }
        }
    }

    private static void closePipe(ParcelFileDescriptor[] pipe) {
        if (pipe == null) return;
        for (ParcelFileDescriptor descriptor : pipe) closeDescriptor(descriptor);
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort during fail-closed teardown.
        }
    }
}
