package com.aios.modelbroker;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.aios.model.AudioStreamFormat;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;
import com.aios.runtime.common.RuntimeMemoryTrimPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Privileged entry point for on-device inference.
 *
 * Capabilities become visible only after catalog admission, artifact digest
 * verification, and exact runtime-provider discovery succeed. A fail-closed
 * broker is safe to include in early bring-up images: clients get a typed error
 * and telephony/media continue without AI.
 */
public final class ModelBrokerService extends Service {
    private static final String TAG = "AiosModelBroker";
    public static final String PERMISSION_USE_MODEL_BROKER =
            "com.aios.permission.USE_MODEL_BROKER";
    public static final int ERROR_NOT_READY = 1;
    public static final int ERROR_INVALID_REQUEST = 2;
    public static final int ERROR_BUSY = 3;
    public static final int ERROR_PREEMPTED = 4;
    public static final int ERROR_RUNTIME_FAILED = 5;
    public static final int ERROR_DEADLINE_EXCEEDED = 6;
    private static final long CALL_STATE_RETRY_MILLIS = 100L;

    private final Object callActivityLock = new Object();
    private final CallActivityLeaseTracker<IBinder> callActivityLeases =
            new CallActivityLeaseTracker<>();
    private final Map<IBinder, IBinder.DeathRecipient> callActivityDeaths =
            new HashMap<>();
    private final Runnable reconcileCallActivityRunnable = this::reconcileCallActivity;
    private BrokerState state;
    private SessionController sessions;
    private Handler mainHandler;
    private boolean appliedCallActive;
    private boolean callActivityUpdateScheduled;
    private boolean stopping;

    private final IAiosModelService.Stub binder = new IAiosModelService.Stub() {
        @Override
        public List<ModelCapability> listCapabilities() {
            enforceBrokerPermission();
            AuthorizedClientPolicy.Rule client = state.requireClient(Binder.getCallingUid());
            return state.capabilitiesFor(client);
        }

        @Override
        public void setCallActive(IBinder lifecycleToken, boolean active) {
            enforceBrokerPermission();
            int callerUid = Binder.getCallingUid();
            AuthorizedClientPolicy.Rule client = state.requireClient(callerUid);
            state.requireCallStateController(client);
            updateCallActivityLease(callerUid, lifecycleToken, active);
        }

        @Override
        public long createSession(ModelRequest request, IModelCallback callback) {
            enforceBrokerPermission();
            if (request == null || callback == null) {
                notifyError(callback, ERROR_INVALID_REQUEST, "request and callback are required");
                return -1L;
            }
            try {
                AuthorizedClientPolicy.Rule client = state.requireClient(Binder.getCallingUid());
                List<VerifiedArtifact> candidates = state.validateRequest(client, request);
                if (candidates.stream().noneMatch(state::runtimeAvailable)) {
                    notifyError(callback, ERROR_NOT_READY,
                            "verified artifact chain has no active runtime adapter");
                    return -1L;
                }
                return sessions.create(
                        Binder.getCallingUid(), client, candidates, request, callback);
            } catch (BrokerState.ResourcePressureException error) {
                notifyError(callback, ERROR_BUSY, error.getMessage());
                return -1L;
            } catch (IllegalArgumentException error) {
                notifyError(callback, ERROR_INVALID_REQUEST, error.getMessage());
                return -1L;
            }
        }

        @Override
        public void submitText(long sessionId, String text, boolean endOfInput) {
            enforceBrokerPermission();
            sessions.submitText(Binder.getCallingUid(), sessionId, text, endOfInput);
        }

        @Override
        public void submitAudio(
                long sessionId,
                ParcelFileDescriptor pcmStream,
                AudioStreamFormat format,
                boolean endOfInput) {
            enforceBrokerPermission();
            sessions.submitAudio(
                    Binder.getCallingUid(), sessionId, pcmStream, format, endOfInput);
        }

        @Override
        public void attachAudioOutput(
                long sessionId,
                ParcelFileDescriptor pcmSink,
                AudioStreamFormat format) {
            enforceBrokerPermission();
            sessions.attachAudioOutput(
                    Binder.getCallingUid(), sessionId, pcmSink, format);
        }

        @Override
        public void submitMedia(
                long sessionId,
                ParcelFileDescriptor media,
                String mimeType,
                boolean endOfInput) {
            enforceBrokerPermission();
            sessions.submitMedia(
                    Binder.getCallingUid(), sessionId, media, mimeType, endOfInput);
        }

        @Override
        public void cancel(long sessionId) {
            enforceBrokerPermission();
            sessions.cancel(Binder.getCallingUid(), sessionId);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        state = BrokerState.load(this);
        sessions = new SessionController(
                state.runtimes(), state.sessionCapacityPolicy());
    }

    @Override
    public IBinder onBind(Intent intent) {
        enforceBrokerPermission();
        return binder;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (RuntimeMemoryTrimPolicy.isMemoryPressure(level) && sessions != null) {
            sessions.onMemoryPressure();
        }
    }

    @Override
    public void onDestroy() {
        stopCallActivityTracking();
        if (sessions != null) {
            sessions.close();
        }
        if (state != null) {
            state.close();
        }
        super.onDestroy();
    }

    private void enforceBrokerPermission() {
        enforceCallingOrSelfPermission(
                PERMISSION_USE_MODEL_BROKER,
                "caller is not authorized to use AIOS models");
    }

    private void updateCallActivityLease(int ownerUid, IBinder token, boolean active) {
        if (token == null) {
            throw new IllegalArgumentException("call-activity lifecycle token is required");
        }
        IBinder.DeathRecipient removedRecipient = null;
        synchronized (callActivityLock) {
            if (stopping) {
                throw new IllegalStateException("model broker is stopping");
            }
            if (active) {
                Integer existingOwner = callActivityLeases.ownerUid(token);
                if (existingOwner != null) {
                    callActivityLeases.acquire(token, ownerUid);
                    return;
                }
                IBinder.DeathRecipient recipient = () -> onCallActivityTokenDied(token);
                try {
                    token.linkToDeath(recipient, 0);
                } catch (RemoteException error) {
                    throw new IllegalArgumentException(
                            "call-activity lifecycle token is already dead", error);
                }
                try {
                    callActivityLeases.acquire(token, ownerUid);
                    callActivityDeaths.put(token, recipient);
                } catch (RuntimeException error) {
                    token.unlinkToDeath(recipient, 0);
                    throw error;
                }
            } else {
                callActivityLeases.release(token, ownerUid);
                removedRecipient = callActivityDeaths.remove(token);
            }
            publishCallActivityStateLocked();
        }
        if (removedRecipient != null) {
            token.unlinkToDeath(removedRecipient, 0);
        }
    }

    private void onCallActivityTokenDied(IBinder token) {
        synchronized (callActivityLock) {
            if (stopping) {
                return;
            }
            callActivityDeaths.remove(token);
            if (callActivityLeases.removeDead(token)) {
                publishCallActivityStateLocked();
            }
        }
    }

    /** Keeps request admission synchronous while runtime preemption stays serialized. */
    private void publishCallActivityStateLocked() {
        boolean active = callActivityLeases.isActive();
        state.setCallActive(active);
        if (appliedCallActive == active || callActivityUpdateScheduled) {
            return;
        }
        callActivityUpdateScheduled = true;
        if (!mainHandler.post(reconcileCallActivityRunnable)) {
            callActivityUpdateScheduled = false;
            Log.e(TAG, "could not schedule call-priority reconciliation");
        }
    }

    private void reconcileCallActivity() {
        while (true) {
            final boolean desired;
            synchronized (callActivityLock) {
                if (stopping) {
                    callActivityUpdateScheduled = false;
                    return;
                }
                desired = callActivityLeases.isActive();
                if (appliedCallActive == desired) {
                    callActivityUpdateScheduled = false;
                    return;
                }
            }
            try {
                sessions.setCallActive(desired);
            } catch (RuntimeException error) {
                Log.e(TAG, "call-priority reconciliation failed; retrying", error);
                if (!mainHandler.postDelayed(
                        reconcileCallActivityRunnable, CALL_STATE_RETRY_MILLIS)) {
                    synchronized (callActivityLock) {
                        callActivityUpdateScheduled = false;
                    }
                }
                return;
            }
            synchronized (callActivityLock) {
                appliedCallActive = desired;
            }
        }
    }

    private void stopCallActivityTracking() {
        List<Map.Entry<IBinder, IBinder.DeathRecipient>> deaths;
        synchronized (callActivityLock) {
            stopping = true;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(reconcileCallActivityRunnable);
            }
            deaths = new ArrayList<>(callActivityDeaths.entrySet());
            callActivityDeaths.clear();
            callActivityLeases.clear();
            callActivityUpdateScheduled = false;
            if (state != null) {
                state.setCallActive(false);
            }
        }
        for (Map.Entry<IBinder, IBinder.DeathRecipient> death : deaths) {
            death.getKey().unlinkToDeath(death.getValue(), 0);
        }
    }

    private static void notifyError(IModelCallback callback, int code, String message) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(code, message);
        } catch (RemoteException ignored) {
            // Client death is equivalent to cancellation.
        }
    }

}
