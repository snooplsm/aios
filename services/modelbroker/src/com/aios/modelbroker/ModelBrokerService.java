package com.aios.modelbroker;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.aios.model.AudioStreamFormat;
import com.aios.model.IAiosModelService;
import com.aios.model.IModelCallback;
import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.util.List;

/**
 * Privileged entry point for on-device inference.
 *
 * This first implementation deliberately advertises no capabilities until the
 * catalog loader, digest verification, and runtime adapters are connected. A
 * fail-closed broker is safe to include in early bring-up images: clients get a
 * typed error and telephony/media continue without AI.
 */
public final class ModelBrokerService extends Service {
    public static final String PERMISSION_USE_MODEL_BROKER =
            "com.aios.permission.USE_MODEL_BROKER";
    public static final int ERROR_NOT_READY = 1;
    public static final int ERROR_INVALID_REQUEST = 2;
    public static final int ERROR_BUSY = 3;
    public static final int ERROR_PREEMPTED = 4;
    public static final int ERROR_RUNTIME_FAILED = 5;
    private BrokerState state;
    private SessionController sessions;

    private final IAiosModelService.Stub binder = new IAiosModelService.Stub() {
        @Override
        public List<ModelCapability> listCapabilities() {
            enforceBrokerPermission();
            AuthorizedClientPolicy.Rule client = state.requireClient(Binder.getCallingUid());
            return state.capabilitiesFor(client);
        }

        @Override
        public void setCallActive(boolean active) {
            enforceBrokerPermission();
            AuthorizedClientPolicy.Rule client = state.requireClient(Binder.getCallingUid());
            state.setCallActive(client, active);
            sessions.setCallActive(active);
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
                VerifiedArtifact artifact = state.validateRequest(client, request);
                if (!state.runtimeAvailable(artifact)) {
                    notifyError(callback, ERROR_NOT_READY,
                            "verified artifact has no active runtime adapter");
                    return -1L;
                }
                return sessions.create(
                        Binder.getCallingUid(), client, artifact, request, callback);
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
        state = BrokerState.load(this);
        // Two whisper streams (RX/TX) plus one LiteRT call-agent request.
        sessions = new SessionController(state.runtimes(), 3);
    }

    @Override
    public IBinder onBind(Intent intent) {
        enforceBrokerPermission();
        return binder;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && sessions != null) {
            sessions.onMemoryPressure();
        }
    }

    @Override
    public void onDestroy() {
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
