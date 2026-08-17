package com.aios.modelbroker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.AudioStreamFormat;
import com.aios.model.GenerationChunk;
import com.aios.model.IModelCallback;
import com.aios.model.InferenceResult;
import com.aios.model.ModelRequest;
import com.aios.runtime.IAiosRuntimeProvider;
import com.aios.runtime.RuntimeArtifact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Binds one allowlisted, platform-signed runtime in a separate process. */
final class RemoteRuntimeAdapter implements RuntimeAdapter {
    private static final String TAG = "AiosRemoteRuntime";
    private static final String PROVIDER_PERMISSION =
            "com.aios.permission.PROVIDE_MODEL_RUNTIME";
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000L;
    private static final long AVAILABILITY_LOG_INTERVAL_MILLIS = 5_000L;

    static final class Spec {
        final int apiVersion;
        final String runtimeId;
        final String packageName;
        final String serviceClass;
        final String action;
        final String implementationVersion;
        final Set<String> allowedBackends;

        Spec(
                int apiVersion,
                String runtimeId,
                String packageName,
                String serviceClass,
                String action,
                String implementationVersion,
                Set<String> allowedBackends) {
            this.apiVersion = apiVersion;
            this.runtimeId = runtimeId;
            this.packageName = packageName;
            this.serviceClass = serviceClass;
            this.action = action;
            this.implementationVersion = implementationVersion;
            this.allowedBackends = Set.copyOf(allowedBackends);
        }
    }

    private final Context context;
    private final Spec spec;
    private final Set<RemoteSession> sessions = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RuntimeRebindPolicy rebindPolicy = new RuntimeRebindPolicy();
    private IAiosRuntimeProvider provider;
    private Set<String> providerBackends = Set.of();
    private ProviderConnection activeConnection;
    private final ServiceConnection priorityConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "PRIORITY_CONNECTED runtime=" + spec.runtimeId);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The verified connection owns provider death and session failure.
        }
    };
    private int priorityLeases;
    private boolean priorityBound;
    private boolean binding;
    private boolean closed;
    private long lastAvailabilityWarningMillis;

    private final Runnable rebind = () -> {
        if (rebindPolicy.begin()) {
            bindVerifiedProvider();
        }
    };

    private final class ProviderConnection implements ServiceConnection {
        final Runnable timeout = () -> onConnectionTimedOut(this);

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosRuntimeProvider candidate = IAiosRuntimeProvider.Stub.asInterface(binder);
            try {
                if (candidate == null
                        || candidate.getProviderApiVersion() != spec.apiVersion
                        || !spec.runtimeId.equals(candidate.getRuntimeId())
                        || !spec.implementationVersion.equals(
                                candidate.getImplementationVersion())) {
                    Log.e(TAG, "runtime identity mismatch for " + name.flattenToShortString());
                    return;
                }
                Set<String> backends = new HashSet<>();
                String[] advertised = candidate.getSupportedBackends();
                if (advertised != null) {
                    for (String backend : advertised) {
                        if (spec.allowedBackends.contains(backend)) {
                            backends.add(backend);
                        }
                    }
                }
                synchronized (RemoteRuntimeAdapter.this) {
                    if (closed || activeConnection != this) {
                        return;
                    }
                    provider = candidate;
                    providerBackends = Set.copyOf(backends);
                }
                mainHandler.removeCallbacks(timeout);
                rebindPolicy.connected();
                Log.i(TAG, "connected " + spec.runtimeId + " "
                        + spec.implementationVersion + " with " + backends);
            } catch (RemoteException | RuntimeException error) {
                Log.e(TAG, "runtime identity query failed", error);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Android retains an ordinary crash binding and reconnects it.
            Log.w(TAG, "DISCONNECTED runtime=" + spec.runtimeId
                    + " component=" + name.flattenToShortString());
            failCurrent(this, "runtime provider disconnected");
            armConnectionTimeout(this);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // This binding can never reconnect. Release it and bind a fresh
            // ServiceConnection immediately, as required by the platform API.
            Log.w(TAG, "BINDING_DIED runtime=" + spec.runtimeId
                    + " component=" + name.flattenToShortString());
            replaceTerminalBinding(this, "runtime provider binding died", true);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // A null binding is permanently unusable and must also be released.
            Log.e(TAG, "NULL_BINDING runtime=" + spec.runtimeId
                    + " component=" + name.flattenToShortString());
            replaceTerminalBinding(
                    this, "runtime provider returned a null binding", false);
        }
    }

    RemoteRuntimeAdapter(Context context, Spec spec) {
        this.context = context.getApplicationContext();
        this.spec = spec;
    }

    @Override
    public String runtimeId() {
        return spec.runtimeId;
    }

    @Override
    public boolean supportsBackend(String backend) {
        ProviderConnection deadConnection = null;
        boolean needsInitialBind = false;
        boolean available;
        synchronized (this) {
            boolean providerConnected = provider != null;
            boolean binderAlive = providerConnected && provider.asBinder().isBinderAlive();
            available = binderAlive && providerBackends.contains(backend);
            if (!available) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastAvailabilityWarningMillis
                        >= AVAILABILITY_LOG_INTERVAL_MILLIS) {
                    lastAvailabilityWarningMillis = now;
                    Log.w(TAG, "UNAVAILABLE runtime=" + spec.runtimeId
                            + " requested_backend=" + backend
                            + " provider_connected=" + providerConnected
                            + " binder_alive=" + binderAlive
                            + " advertised_backends=" + providerBackends
                            + " allowed_backends=" + spec.allowedBackends
                            + " active_binding=" + (activeConnection != null)
                            + " binding=" + binding);
                }
                if (providerConnected && !binderAlive) {
                    deadConnection = activeConnection;
                } else if (!providerConnected && activeConnection == null && !binding) {
                    needsInitialBind = true;
                }
            }
        }
        if (deadConnection != null) {
            replaceTerminalBinding(
                    deadConnection, "runtime provider binder is dead", true);
        } else if (needsInitialBind) {
            scheduleRebind(true);
        }
        return available;
    }

    @Override
    public Session open(
            VerifiedArtifact artifact,
            ModelRequest request,
            IModelCallback callback) throws IOException, RemoteException {
        IAiosRuntimeProvider current;
        synchronized (this) {
            current = provider;
            if (current == null || !providerBackends.contains(artifact.backend)) {
                throw new IOException("runtime provider is not ready for " + artifact.backend);
            }
        }
        RuntimeArtifact transport = new RuntimeArtifact();
        transport.modelId = artifact.modelId;
        transport.modelPath = artifact.file.getAbsolutePath();
        transport.modelDigest = artifact.sha256;
        transport.sizeBytes = artifact.sizeBytes;
        transport.backend = artifact.backend;

        Log.i(TAG, "OPEN runtime=" + spec.runtimeId
                + " capability=" + request.capability
                + " model=" + artifact.modelId
                + " backend=" + artifact.backend
                + " request=" + request.requestId);
        acquirePriorityBinding();
        RemoteSession session = new RemoteSession(
                current, callback, request.capability, artifact.modelId, request.requestId);
        try {
            long providerSessionId = current.createSession(transport, request, session.transport);
            if (providerSessionId <= 0L) {
                throw new IOException("runtime provider rejected the session");
            }
            session.attach(providerSessionId);
            boolean accepted;
            synchronized (this) {
                accepted = !closed && provider == current && !session.closed;
                if (accepted) {
                    sessions.add(session);
                }
            }
            if (!accepted) {
                throw new IOException("runtime provider changed while opening the session");
            }
            return session;
        } catch (IOException | RemoteException | RuntimeException error) {
            session.close();
            throw error;
        }
    }

    @Override
    public void start() {
        synchronized (this) {
            if (closed || activeConnection != null || binding) {
                return;
            }
        }
        scheduleRebind(true);
    }

    @Override
    public void close() {
        ProviderConnection connection;
        ArrayList<RemoteSession> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            provider = null;
            providerBackends = Set.of();
            connection = activeConnection;
            activeConnection = null;
            binding = false;
            snapshot = new ArrayList<>(sessions);
            sessions.clear();
        }
        rebindPolicy.close();
        mainHandler.removeCallbacks(rebind);
        failSessions(snapshot, "runtime registry closed");
        if (connection != null) {
            mainHandler.removeCallbacks(connection.timeout);
        }
        unbindQuietly(connection);
    }

    private void failCurrent(ProviderConnection connection, String message) {
        ArrayList<RemoteSession> snapshot;
        synchronized (this) {
            if (closed || activeConnection != connection) {
                return;
            }
            provider = null;
            providerBackends = Set.of();
            snapshot = new ArrayList<>(sessions);
            sessions.clear();
        }
        failSessions(snapshot, message);
    }

    private void replaceTerminalBinding(
            ProviderConnection connection, String message, boolean immediate) {
        ArrayList<RemoteSession> snapshot;
        synchronized (this) {
            if (closed || activeConnection != connection) {
                return;
            }
            provider = null;
            providerBackends = Set.of();
            activeConnection = null;
            binding = false;
            snapshot = new ArrayList<>(sessions);
            sessions.clear();
        }
        failSessions(snapshot, message);
        mainHandler.removeCallbacks(connection.timeout);
        unbindQuietly(connection);
        scheduleRebind(immediate);
    }

    private void bindVerifiedProvider() {
        Intent intent = verifiedProviderIntent();
        if (intent == null) {
            Log.i(TAG, "verified provider absent for " + spec.runtimeId);
            scheduleRebind(false);
            return;
        }

        ProviderConnection connection = new ProviderConnection();
        synchronized (this) {
            if (closed || activeConnection != null || binding) {
                return;
            }
            activeConnection = connection;
            binding = true;
        }

        boolean didBind = false;
        try {
            didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            Log.e(TAG, "verified provider bind failed for " + spec.runtimeId, error);
        }

        boolean release = false;
        boolean retry = false;
        synchronized (this) {
            if (activeConnection != connection) {
                release = didBind;
            } else {
                binding = false;
                if (closed) {
                    activeConnection = null;
                    release = didBind;
                } else if (!didBind) {
                    activeConnection = null;
                    retry = true;
                }
            }
        }
        if (release) {
            unbindQuietly(connection);
        } else if (retry) {
            Log.e(TAG, "could not bind verified provider for " + spec.runtimeId);
            scheduleRebind(false);
        } else {
            armConnectionTimeout(connection);
        }
    }

    private void armConnectionTimeout(ProviderConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || provider != null) {
                return;
            }
        }
        mainHandler.removeCallbacks(connection.timeout);
        mainHandler.postDelayed(connection.timeout, CONNECT_TIMEOUT_MILLIS);
    }

    private void onConnectionTimedOut(ProviderConnection connection) {
        synchronized (this) {
            if (closed || activeConnection != connection || provider != null) {
                return;
            }
        }
        Log.e(TAG, "CONNECT_TIMEOUT runtime=" + spec.runtimeId);
        replaceTerminalBinding(
                connection, "runtime provider connection timed out", false);
    }

    private Intent verifiedProviderIntent() {
        Intent intent = new Intent(spec.action)
                .setComponent(new ComponentName(spec.packageName, spec.serviceClass));
        PackageManager packages = context.getPackageManager();
        try {
            ResolveInfo resolved = packages.resolveService(
                    intent, PackageManager.MATCH_SYSTEM_ONLY);
            if (resolved == null || resolved.serviceInfo == null
                    || !spec.packageName.equals(resolved.serviceInfo.packageName)
                    || !spec.serviceClass.equals(resolved.serviceInfo.name)
                    || (resolved.serviceInfo.applicationInfo.flags
                    & ApplicationInfo.FLAG_SYSTEM) == 0
                    || !PROVIDER_PERMISSION.equals(resolved.serviceInfo.permission)
                    || packages.checkPermission(PROVIDER_PERMISSION, spec.packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "runtime provider verification failed for " + spec.runtimeId, error);
            return null;
        }
        return intent;
    }

    private void scheduleRebind(boolean immediate) {
        long delay = rebindPolicy.reserve(immediate);
        if (delay != RuntimeRebindPolicy.NO_RETRY) {
            mainHandler.postDelayed(rebind, delay);
        }
    }

    /**
     * Adds a second binding only while inference is active. BIND_IMPORTANT
     * moves the provider out of Android's restricted/background CPU set, while
     * the ordinary verified binding can remain cheap and connected when idle.
     */
    private synchronized void acquirePriorityBinding() throws IOException {
        if (closed || provider == null) {
            throw new IOException("runtime provider closed before priority acquisition");
        }
        if (priorityLeases == 0) {
            Intent intent = new Intent(spec.action)
                    .setComponent(new ComponentName(spec.packageName, spec.serviceClass));
            boolean didBind;
            try {
                didBind = context.bindService(
                        intent,
                        priorityConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            } catch (RuntimeException error) {
                throw new IOException("runtime priority bind failed", error);
            }
            if (!didBind) {
                throw new IOException("runtime priority bind was rejected");
            }
            priorityBound = true;
            Log.i(TAG, "PRIORITY_ACQUIRED runtime=" + spec.runtimeId);
        }
        priorityLeases++;
    }

    private void releasePriorityBinding() {
        boolean release = false;
        synchronized (this) {
            if (priorityLeases <= 0) {
                return;
            }
            priorityLeases--;
            if (priorityLeases == 0 && priorityBound) {
                priorityBound = false;
                release = true;
            }
        }
        if (release) {
            unbindQuietly(priorityConnection);
            Log.i(TAG, "PRIORITY_RELEASED runtime=" + spec.runtimeId);
        }
    }

    private void unbindQuietly(ServiceConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // A close can race the completion of bindService(). The successful
            // attempt observes the cleared activeConnection and retries unbind.
        }
    }

    private static void failSessions(
            ArrayList<RemoteSession> sessions, String message) {
        for (RemoteSession session : sessions) {
            session.fail(message);
        }
    }

    private final class RemoteSession implements RuntimeAdapter.Session {
        private final IAiosRuntimeProvider owner;
        private final IModelCallback callback;
        private final String capability;
        private final String modelId;
        private final String requestId;
        private final long createdAt = SystemClock.elapsedRealtime();
        private final AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
        private final AtomicBoolean priorityReleased = new AtomicBoolean(false);
        private long id = -1L;
        private volatile boolean closed;

        final IModelCallback transport = new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                if (firstChunkLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "FIRST_CHUNK runtime=" + spec.runtimeId
                            + " capability=" + capability
                            + " model=" + modelId
                            + " request=" + requestId
                            + " elapsed_ms="
                            + (SystemClock.elapsedRealtime() - createdAt));
                }
                try {
                    callback.onChunk(chunk);
                } catch (RemoteException error) {
                    close();
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
                Log.i(TAG, "COMPLETED runtime=" + spec.runtimeId
                        + " capability=" + capability
                        + " model=" + modelId
                        + " request=" + requestId
                        + " elapsed_ms=" + (SystemClock.elapsedRealtime() - createdAt));
                try {
                    callback.onCompleted(result);
                } catch (RemoteException ignored) {
                    // Client death has the same lifecycle result.
                } finally {
                    finish();
                }
            }

            @Override
            public void onError(int code, String message) {
                Log.e(TAG, "ERROR runtime=" + spec.runtimeId
                        + " capability=" + capability
                        + " model=" + modelId
                        + " request=" + requestId
                        + " elapsed_ms=" + (SystemClock.elapsedRealtime() - createdAt)
                        + " code=" + code + " message=" + message);
                try {
                    callback.onError(code, message);
                } catch (RemoteException ignored) {
                    // Client death has the same lifecycle result.
                } finally {
                    finish();
                }
            }
        };

        RemoteSession(
                IAiosRuntimeProvider owner,
                IModelCallback callback,
                String capability,
                String modelId,
                String requestId) {
            this.owner = owner;
            this.callback = callback;
            this.capability = capability;
            this.modelId = modelId;
            this.requestId = requestId;
        }

        synchronized void attach(long sessionId) {
            id = sessionId;
            if (closed) {
                try {
                    owner.cancel(id);
                } catch (RemoteException ignored) {
                    // Provider already died.
                }
            }
        }

        @Override
        public synchronized void submitText(String text, boolean endOfInput)
                throws RemoteException {
            requireOpen();
            Log.i(TAG, "SUBMIT_TEXT runtime=" + spec.runtimeId
                    + " request=" + requestId + " chars="
                    + (text == null ? 0 : text.length()));
            owner.submitText(id, text, endOfInput);
        }

        @Override
        public synchronized void submitAudio(
                ParcelFileDescriptor pcmStream,
                AudioStreamFormat format,
                boolean endOfInput) throws RemoteException {
            requireOpen();
            Log.i(TAG, "SUBMIT_AUDIO runtime=" + spec.runtimeId
                    + " request=" + requestId);
            owner.submitAudio(id, pcmStream, format, endOfInput);
        }

        @Override
        public synchronized void attachAudioOutput(
                ParcelFileDescriptor pcmSink,
                AudioStreamFormat format) throws RemoteException {
            requireOpen();
            Log.i(TAG, "ATTACH_AUDIO_OUTPUT runtime=" + spec.runtimeId
                    + " request=" + requestId);
            owner.attachAudioOutput(id, pcmSink, format);
        }

        @Override
        public synchronized void submitMedia(
                ParcelFileDescriptor media,
                String mimeType,
                boolean endOfInput) throws RemoteException {
            requireOpen();
            Log.i(TAG, "SUBMIT_MEDIA runtime=" + spec.runtimeId
                    + " request=" + requestId + " mime=" + mimeType);
            owner.submitMedia(id, media, mimeType, endOfInput);
        }

        @Override
        public void close() {
            long sessionId;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                sessionId = id;
            }
            synchronized (RemoteRuntimeAdapter.this) {
                sessions.remove(this);
            }
            if (sessionId > 0L) {
                try {
                    owner.cancel(sessionId);
                } catch (RemoteException ignored) {
                    // Provider already died.
                }
            }
            releasePriorityOnce();
        }

        void fail(String message) {
            try {
                callback.onError(ModelBrokerService.ERROR_RUNTIME_FAILED, message);
            } catch (RemoteException ignored) {
                // Client is gone too.
            } finally {
                finish();
            }
        }

        private void finish() {
            synchronized (this) {
                closed = true;
            }
            synchronized (RemoteRuntimeAdapter.this) {
                sessions.remove(this);
            }
            releasePriorityOnce();
        }

        private void releasePriorityOnce() {
            if (priorityReleased.compareAndSet(false, true)) {
                releasePriorityBinding();
            }
        }

        private void requireOpen() throws RemoteException {
            if (closed || id <= 0L) {
                throw new RemoteException("runtime session is closed");
            }
        }
    }
}
