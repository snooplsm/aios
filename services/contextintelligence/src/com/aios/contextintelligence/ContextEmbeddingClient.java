package com.aios.contextintelligence;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.GenerationChunk;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Fail-optional embedding bridge; SQL/FTS remains authoritative when it is unavailable. */
final class ContextEmbeddingClient implements AutoCloseable {
    static final long QUERY_WAIT_MILLIS = 250L;
    private static final String TAG = "AiosContextEmbedding";
    private static final String BROKER_ACTION = "com.aios.model.MODEL_SERVICE";
    private static final String BROKER_PACKAGE = "com.aios.modelbroker";
    private static final String CAPABILITY = EmbeddingCapabilityPolicy.CAPABILITY;
    private static final String LANGUAGE = EmbeddingCapabilityPolicy.LANGUAGE;
    private static final long QUERY_DEADLINE_MILLIS = 1_000L;
    private static final long DOCUMENT_DEADLINE_MILLIS = 30_000L;
    private static final long RETRY_MILLIS = 60_000L;

    static final class QueryVector {
        final String modelId;
        final String modelDigest;
        final float[] values;

        QueryVector(String modelId, String modelDigest, float[] values) {
            EmbeddingModelIdentity.validate(modelId, modelDigest);
            QuantizedEmbedding.quantize(values);
            this.modelId = modelId;
            this.modelDigest = modelDigest;
            this.values = Arrays.copyOf(values, values.length);
        }
    }

    private static final class SelectedModel {
        final String modelId;
        final String modelDigest;

        SelectedModel(String modelId, String modelDigest) {
            EmbeddingModelIdentity.validate(modelId, modelDigest);
            this.modelId = modelId;
            this.modelDigest = modelDigest;
        }
    }

    private static final class Result {
        final float[] values;

        Result(float[] values) {
            QuantizedEmbedding.quantize(values);
            this.values = Arrays.copyOf(values, values.length);
        }
    }

    private final Context context;
    private final ContextStore store;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "aios-context-embedding");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicLong nextRequest = new AtomicLong();
    private final Set<CompletableFuture<Result>> active = new HashSet<>();
    private IAiosModelService service;
    private SelectedModel selectedModel;
    private boolean bound;
    private boolean binding;
    private boolean indexingScheduled;
    private boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosModelService candidate = IAiosModelService.Stub.asInterface(binder);
            synchronized (ContextEmbeddingClient.this) {
                if (closed || candidate == null) return;
                service = candidate;
                binding = false;
            }
            executeWorker(() -> loadCapability(candidate));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            brokerUnavailable();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            replaceBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            replaceBinding();
        }
    };

    ContextEmbeddingClient(Context context, ContextStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    void start() {
        context.getMainExecutor().execute(this::bind);
    }

    void scheduleIndexing() {
        synchronized (this) {
            if (closed || indexingScheduled || selectedModel == null || service == null) return;
            indexingScheduled = true;
        }
        if (!executeWorker(this::drainIndex)) {
            synchronized (this) {
                indexingScheduled = false;
            }
        }
    }

    QueryVector embedQuery(String text) {
        IAiosModelService broker;
        SelectedModel model;
        synchronized (this) {
            if (closed || service == null || selectedModel == null) return null;
            broker = service;
            model = selectedModel;
        }
        Result result = request(
                broker,
                model,
                "query",
                "context_query",
                text,
                QUERY_DEADLINE_MILLIS,
                QUERY_WAIT_MILLIS);
        return result == null ? null
                : new QueryVector(model.modelId, model.modelDigest, result.values);
    }

    @Override
    public void close() {
        Set<CompletableFuture<Result>> pending;
        boolean release;
        synchronized (this) {
            if (closed) return;
            closed = true;
            service = null;
            selectedModel = null;
            pending = new HashSet<>(active);
            active.clear();
            release = bound;
            bound = false;
            binding = false;
        }
        for (CompletableFuture<Result> future : pending) future.complete(null);
        worker.shutdownNow();
        if (release) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Close can race terminal binding replacement.
            }
        }
    }

    private void bind() {
        synchronized (this) {
            if (closed || bound || binding) return;
            binding = true;
        }
        boolean didBind = false;
        try {
            didBind = context.bindService(
                    new Intent(BROKER_ACTION).setPackage(BROKER_PACKAGE),
                    connection,
                    Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            Log.w(TAG, "Model Broker bind failed", error);
        }
        synchronized (this) {
            binding = false;
            if (closed) {
                if (didBind) {
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                        // The binding may already have terminated.
                    }
                }
                return;
            }
            bound = didBind;
        }
        if (!didBind) scheduleRebind();
    }

    private void loadCapability(IAiosModelService candidate) {
        SelectedModel found = null;
        try {
            for (ModelCapability capability : candidate.listCapabilities()) {
                if (capability != null && EmbeddingCapabilityPolicy.accepts(
                        capability.capability,
                        capability.available,
                        capability.languages,
                        capability.selectedModelId,
                        capability.selectedModelDigest)) {
                    found = new SelectedModel(
                            capability.selectedModelId,
                            capability.selectedModelDigest);
                    break;
                }
            }
        } catch (RemoteException | RuntimeException error) {
            Log.i(TAG, "Embedding capability is not active", error);
        }
        synchronized (this) {
            if (closed || service != candidate) return;
            selectedModel = found;
        }
        if (found != null) scheduleIndexing();
    }

    private void drainIndex() {
        try {
            while (true) {
                IAiosModelService broker;
                SelectedModel model;
                synchronized (this) {
                    if (closed || service == null || selectedModel == null) return;
                    broker = service;
                    model = selectedModel;
                }
                List<EmbeddingWorkItem> work = store.pendingEmbeddings(
                        model.modelId,
                        model.modelDigest,
                        16,
                        System.currentTimeMillis());
                if (work.isEmpty()) return;
                for (EmbeddingWorkItem item : work) {
                    Result result = request(
                            broker,
                            model,
                            "document",
                            "context_background",
                            item.text,
                            DOCUMENT_DEADLINE_MILLIS,
                            DOCUMENT_DEADLINE_MILLIS);
                    if (result == null) {
                        scheduleIndexRetry();
                        return;
                    }
                    store.commitEmbedding(
                            item.sourceType,
                            item.sourceId,
                            item.revision,
                            model.modelId,
                            model.modelDigest,
                            QuantizedEmbedding.quantize(result.values),
                            System.currentTimeMillis());
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Background context embedding stopped", error);
            scheduleIndexRetry();
        } finally {
            synchronized (this) {
                indexingScheduled = false;
            }
        }
    }

    private Result request(
            IAiosModelService broker,
            SelectedModel model,
            String task,
            String workload,
            String text,
            long deadlineMillis,
            long waitMillis) {
        if (text == null || text.isBlank() || text.length() > ContextPolicy.MAX_DOCUMENT_CHARS) {
            return null;
        }
        long serial = nextRequest.updateAndGet(value -> value == Long.MAX_VALUE ? 1L : value + 1L);
        String requestId = "context:" + task + ":" + serial;
        CompletableFuture<Result> future = new CompletableFuture<>();
        synchronized (this) {
            if (closed || service != broker || selectedModel != model) return null;
            active.add(future);
        }
        long sessionId = -1L;
        boolean cancel = false;
        try {
            ModelRequest request = new ModelRequest();
            request.requestId = requestId;
            request.capability = CAPABILITY;
            request.workload = workload;
            request.language = LANGUAGE;
            request.maxOutputTokens = 0;
            request.deadlineElapsedRealtimeMillis =
                    SystemClock.elapsedRealtime() + deadlineMillis;
            request.allowFallback = false;
            request.embeddingTask = task;
            sessionId = broker.createSession(
                    request, callback(requestId, model, future));
            if (sessionId <= 0L) return null;
            broker.submitText(sessionId, text, true);
            Result result = future.get(waitMillis, TimeUnit.MILLISECONDS);
            cancel = result == null;
            return result;
        } catch (RemoteException | ExecutionException | TimeoutException error) {
            cancel = true;
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel = true;
            return null;
        } catch (RuntimeException error) {
            cancel = true;
            return null;
        } finally {
            synchronized (this) {
                active.remove(future);
            }
            if (cancel && sessionId > 0L) {
                try {
                    broker.cancel(sessionId);
                } catch (RemoteException | RuntimeException ignored) {
                    // Broker death also owns runtime cleanup.
                }
            }
        }
    }

    private IModelCallback callback(
            String requestId,
            SelectedModel model,
            CompletableFuture<Result> future) {
        return new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                future.complete(null);
            }

            @Override
            public void onCompleted(InferenceResult result) {
                try {
                    if (result == null
                            || !requestId.equals(result.requestId)
                            || !CAPABILITY.equals(result.capability)
                            || !model.modelId.equals(result.modelId)
                            || !model.modelDigest.equals(result.modelDigest)
                            || !LANGUAGE.equals(result.language)
                            || result.outputJson != null) {
                        future.complete(null);
                        return;
                    }
                    future.complete(new Result(result.embedding));
                } catch (RuntimeException invalid) {
                    future.complete(null);
                }
            }

            @Override
            public void onError(int code, String message) {
                future.complete(null);
            }
        };
    }

    private void brokerUnavailable() {
        Set<CompletableFuture<Result>> pending;
        synchronized (this) {
            service = null;
            selectedModel = null;
            pending = new HashSet<>(active);
        }
        for (CompletableFuture<Result> future : pending) future.complete(null);
    }

    private void replaceBinding() {
        boolean release;
        synchronized (this) {
            if (closed) return;
            release = bound;
            bound = false;
            binding = false;
        }
        brokerUnavailable();
        if (release) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Terminal callbacks can race framework cleanup.
            }
        }
        scheduleRebind();
    }

    private void scheduleRebind() {
        if (worker.isShutdown()) return;
        try {
            worker.schedule(
                    () -> context.getMainExecutor().execute(this::bind),
                    RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Service shutdown won the race.
        }
    }

    private void scheduleIndexRetry() {
        if (worker.isShutdown()) return;
        try {
            worker.schedule(this::scheduleIndexing, RETRY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Service shutdown won the race.
        }
    }

    private boolean executeWorker(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

}
