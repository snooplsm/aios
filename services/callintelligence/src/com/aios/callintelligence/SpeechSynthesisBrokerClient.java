package com.aios.callintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Requests bounded, on-device synthesized PCM from Model Broker. */
final class SpeechSynthesisBrokerClient implements AutoCloseable {
    private static final String BROKER_ACTION = "com.aios.model.MODEL_SERVICE";
    private static final String BROKER_PACKAGE = "com.aios.modelbroker";
    private static final int OUTPUT_SAMPLE_RATE_HZ = 24_000;
    private static final long REQUEST_DEADLINE_MILLIS = 30_000L;
    private static final int MAX_TEXT_CHARS = 2_048;

    interface Listener {
        void onStatus(String requestId, String detail);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "aios-speech-broker");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final Set<Speech> active = new HashSet<>();
    private final Set<String> languages = new HashSet<>();
    private IAiosModelService service;
    private boolean bound;
    private boolean available;
    private boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosModelService candidate = IAiosModelService.Stub.asInterface(binder);
            worker.execute(() -> loadCapabilities(candidate));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearService();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            clearService();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearService();
        }
    };

    SpeechSynthesisBrokerClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    synchronized void start() {
        if (closed || bound) return;
        Intent intent = new Intent(BROKER_ACTION).setPackage(BROKER_PACKAGE);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    synchronized boolean isAvailable(String language) {
        return !closed && service != null && available && languages.contains(language);
    }

    Speech synthesize(String requestId, String language, String text) throws IOException {
        IAiosModelService broker;
        synchronized (this) {
            if (closed || !available || service == null || !languages.contains(language)) {
                throw new IOException("speech synthesis is unavailable");
            }
            broker = service;
        }
        if (requestId == null || requestId.isEmpty() || text == null || text.isBlank()
                || text.length() > MAX_TEXT_CHARS) {
            throw new IOException("invalid speech synthesis request");
        }

        ParcelFileDescriptor[] pipe = null;
        Speech speech = null;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
            AtomicReference<Speech> reference = new AtomicReference<>();
            ModelRequest request = new ModelRequest();
            request.requestId = requestId;
            request.capability = "speech_synthesis";
            request.workload = "call_agent";
            request.language = language;
            request.maxOutputTokens = 0;
            request.deadlineElapsedRealtimeMillis =
                    SystemClock.elapsedRealtime() + REQUEST_DEADLINE_MILLIS;
            request.allowFallback = false;
            long sessionId = broker.createSession(request, callback(requestId, reference));
            if (sessionId <= 0L) {
                throw new IOException("speech synthesis session was rejected");
            }
            speech = new Speech(requestId, broker, sessionId, pipe[0]);
            reference.set(speech);
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
            broker.submitText(sessionId, text, true);
            return speech;
        } catch (IOException error) {
            if (speech != null) speech.close();
            throw error;
        } catch (RemoteException | RuntimeException error) {
            if (speech != null) speech.close();
            throw new IOException("speech synthesis setup failed", error);
        } finally {
            if (speech == null) closePipe(pipe);
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
            if (bound) {
                context.unbindService(connection);
                bound = false;
            }
        }
        for (Speech speech : snapshot) speech.close();
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
            candidate = null;
        }
        synchronized (this) {
            if (closed) return;
            service = candidate;
            available = candidate != null && found;
            languages.clear();
            if (available) languages.addAll(supported);
        }
        notifyStatus("availability", available
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
        for (Speech speech : snapshot) speech.close();
    }

    private IModelCallback callback(
            String requestId, AtomicReference<Speech> reference) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                // Speech audio is transported only through the bounded PCM pipe.
            }

            @Override
            public void onCompleted(InferenceResult result) {
                Speech speech = reference.get();
                if (speech != null) speech.finished();
                notifyStatus(requestId, "speech_synthesis_complete");
            }

            @Override
            public void onError(int code, String message) {
                Speech speech = reference.get();
                if (speech != null) speech.finished();
                notifyStatus(requestId, "speech_synthesis_error_" + code);
            }
        };
    }

    private void notifyStatus(String requestId, String detail) {
        try {
            listener.onStatus(requestId, detail);
        } catch (RuntimeException ignored) {
            // Status reporting cannot own the synthesis lifecycle.
        }
    }

    final class Speech implements AutoCloseable {
        final String requestId;
        final int sampleRateHz = OUTPUT_SAMPLE_RATE_HZ;
        private final IAiosModelService broker;
        private final long sessionId;
        private ParcelFileDescriptor pcmInput;
        private boolean finished;
        private boolean closed;

        Speech(
                String requestId,
                IAiosModelService broker,
                long sessionId,
                ParcelFileDescriptor pcmInput) {
            this.requestId = requestId;
            this.broker = broker;
            this.sessionId = sessionId;
            this.pcmInput = pcmInput;
        }

        synchronized ParcelFileDescriptor takePcmInput() throws IOException {
            if (closed || pcmInput == null) {
                throw new IOException("synthesis PCM input is unavailable");
            }
            ParcelFileDescriptor result = pcmInput;
            pcmInput = null;
            return result;
        }

        synchronized void finished() {
            if (finished) return;
            finished = true;
            synchronized (SpeechSynthesisBrokerClient.this) {
                active.remove(this);
            }
        }

        @Override
        public void close() {
            ParcelFileDescriptor descriptor;
            boolean shouldCancel;
            synchronized (this) {
                if (closed) return;
                closed = true;
                descriptor = pcmInput;
                pcmInput = null;
                shouldCancel = !finished;
            }
            synchronized (SpeechSynthesisBrokerClient.this) {
                active.remove(this);
            }
            closeDescriptor(descriptor);
            if (shouldCancel) {
                try {
                    broker.cancel(sessionId);
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
