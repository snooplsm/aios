package com.aios.modelbroker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
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

/** Binds one allowlisted, platform-signed runtime in a separate process. */
final class RemoteRuntimeAdapter implements RuntimeAdapter {
    private static final String TAG = "AiosRemoteRuntime";
    private static final String PROVIDER_PERMISSION =
            "com.aios.permission.PROVIDE_MODEL_RUNTIME";

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
    private IAiosRuntimeProvider provider;
    private Set<String> providerBackends = Set.of();
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IAiosRuntimeProvider candidate = IAiosRuntimeProvider.Stub.asInterface(binder);
            try {
                if (candidate.getProviderApiVersion() != spec.apiVersion
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
                    provider = candidate;
                    providerBackends = Set.copyOf(backends);
                }
                Log.i(TAG, "connected " + spec.runtimeId + " "
                        + spec.implementationVersion + " with " + backends);
            } catch (RemoteException error) {
                Log.e(TAG, "runtime identity query failed", error);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            failAll("runtime provider disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            failAll("runtime provider binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            failAll("runtime provider returned a null binding");
        }
    };

    RemoteRuntimeAdapter(Context context, Spec spec) {
        this.context = context;
        this.spec = spec;
    }

    @Override
    public String runtimeId() {
        return spec.runtimeId;
    }

    @Override
    public synchronized boolean supportsBackend(String backend) {
        return provider != null && providerBackends.contains(backend);
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

        RemoteSession session = new RemoteSession(current, callback);
        long providerSessionId = current.createSession(transport, request, session.transport);
        if (providerSessionId <= 0L) {
            throw new IOException("runtime provider rejected the session");
        }
        session.attach(providerSessionId);
        synchronized (this) {
            if (!session.closed) {
                sessions.add(session);
            }
        }
        return session;
    }

    @Override
    public void start() {
        Intent intent = new Intent(spec.action)
                .setComponent(new ComponentName(spec.packageName, spec.serviceClass));
        PackageManager packages = context.getPackageManager();
        ResolveInfo resolved = packages.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (resolved == null || resolved.serviceInfo == null
                || !spec.packageName.equals(resolved.serviceInfo.packageName)
                || !spec.serviceClass.equals(resolved.serviceInfo.name)
                || (resolved.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                || !PROVIDER_PERMISSION.equals(resolved.serviceInfo.permission)
                || packages.checkPermission(PROVIDER_PERMISSION, spec.packageName)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "verified provider absent for " + spec.runtimeId);
            return;
        }
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "could not bind verified provider for " + spec.runtimeId);
        }
    }

    @Override
    public void close() {
        failAll("runtime registry closed");
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    private void failAll(String message) {
        ArrayList<RemoteSession> snapshot;
        synchronized (this) {
            provider = null;
            providerBackends = Set.of();
            snapshot = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (RemoteSession session : snapshot) {
            session.fail(message);
        }
    }

    private final class RemoteSession implements RuntimeAdapter.Session {
        private final IAiosRuntimeProvider owner;
        private final IModelCallback callback;
        private long id = -1L;
        private boolean closed;

        final IModelCallback transport = new IModelCallback.Stub() {
            @Override
            public void onChunk(GenerationChunk chunk) {
                try {
                    callback.onChunk(chunk);
                } catch (RemoteException error) {
                    close();
                }
            }

            @Override
            public void onCompleted(InferenceResult result) {
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
                try {
                    callback.onError(code, message);
                } catch (RemoteException ignored) {
                    // Client death has the same lifecycle result.
                } finally {
                    finish();
                }
            }
        };

        RemoteSession(IAiosRuntimeProvider owner, IModelCallback callback) {
            this.owner = owner;
            this.callback = callback;
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
            owner.submitText(id, text, endOfInput);
        }

        @Override
        public synchronized void submitAudio(
                ParcelFileDescriptor pcmStream,
                AudioStreamFormat format,
                boolean endOfInput) throws RemoteException {
            requireOpen();
            owner.submitAudio(id, pcmStream, format, endOfInput);
        }

        @Override
        public synchronized void attachAudioOutput(
                ParcelFileDescriptor pcmSink,
                AudioStreamFormat format) throws RemoteException {
            requireOpen();
            owner.attachAudioOutput(id, pcmSink, format);
        }

        @Override
        public synchronized void submitMedia(
                ParcelFileDescriptor media,
                String mimeType,
                boolean endOfInput) throws RemoteException {
            requireOpen();
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
        }

        private void requireOpen() throws RemoteException {
            if (closed || id <= 0L) {
                throw new RemoteException("runtime session is closed");
            }
        }
    }
}
