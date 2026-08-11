package com.aios.modelbroker;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Verified immutable startup state plus per-caller admission checks. */
final class BrokerState {
    private static final String TAG = "AiosBrokerState";
    private static final long BYTES_PER_MIB = 1024L * 1024L;
    private static final File CONFIGURATION = new File("/product/etc/aios");

    private final AuthorizedClientPolicy clients;
    private final Map<String, VerifiedArtifact> selectedArtifacts;
    private final RuntimeRegistry runtimes;
    private volatile boolean callActive;

    private BrokerState(
            AuthorizedClientPolicy clients,
            Map<String, VerifiedArtifact> selectedArtifacts,
            RuntimeRegistry runtimes) {
        this.clients = clients;
        this.selectedArtifacts = selectedArtifacts;
        this.runtimes = runtimes;
    }

    static BrokerState load(Context context) {
        AuthorizedClientPolicy clients = AuthorizedClientPolicy.denyAll(context);
        Map<String, VerifiedArtifact> selected = Map.of();
        RuntimeRegistry runtimes = RuntimeRegistry.modelFree();
        try {
            runtimes = RuntimeRegistry.load(
                    context, new File(CONFIGURATION, "runtime_catalog.json"));
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "runtime policy failed; runtime activation denied", error);
        }
        try {
            clients = AuthorizedClientPolicy.load(
                    context, new File(CONFIGURATION, "authorized_clients.json"));
            Map<String, VerifiedArtifact> verified =
                    new ArtifactVerifier(CONFIGURATION).verifyAll();
            CatalogPolicy catalog = CatalogPolicy.load(
                    new File(CONFIGURATION, "model_catalog.json"));
            long totalRamMb = totalRamMb(context);
            Map<String, VerifiedArtifact> tierCandidates =
                    catalog.selectForMemory(verified, totalRamMb);
            DeviceModelAdmission admission = DeviceModelAdmission.load(
                    new File(CONFIGURATION, "model_admission.json"));
            DeviceModelAdmission.Selection deviceSelection = admission.select(
                    tierCandidates,
                    Build.DEVICE,
                    totalRamMb,
                    BrokerProductProperties.isDebuggableBuild(),
                    BuildFingerprintPolicy.sha256(Build.FINGERPRINT));
            selected = deviceSelection.artifacts;
            Log.i(TAG, "selected " + selected.size() + " verified model artifact(s)"
                    + " for profile=" + deviceSelection.profileId
                    + " research=" + deviceSelection.researchMode);
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "broker startup policy failed; all inference denied", error);
            clients = AuthorizedClientPolicy.denyAll(context);
            selected = Map.of();
        }
        return new BrokerState(clients, selected, runtimes);
    }

    AuthorizedClientPolicy.Rule requireClient(int uid) {
        AuthorizedClientPolicy.Rule rule = clients.resolveUid(uid);
        if (rule == null) {
            throw new SecurityException("UID is not an authorized AIOS model client");
        }
        return rule;
    }

    List<ModelCapability> capabilitiesFor(AuthorizedClientPolicy.Rule client) {
        Map<String, RuntimeCandidatePolicy.Choice> choices =
                RuntimeCandidatePolicy.capabilities(
                        selectedArtifacts.values(),
                        client.capabilities,
                        runtimes::supports);
        List<ModelCapability> result = new ArrayList<>();
        for (Map.Entry<String, RuntimeCandidatePolicy.Choice> item
                : choices.entrySet()) {
            String capability = item.getKey();
            RuntimeCandidatePolicy.Choice choice = item.getValue();
            ModelCapability value = new ModelCapability();
            value.capability = capability;
            value.selectedModelId = choice.artifact.modelId;
            value.languages = choice.artifact.languages.toArray(new String[0]);
            value.available = choice.available;
            value.streaming = "streaming_asr".equals(capability);
            value.maxConcurrentSessions = Math.min(client.maxSessions, 1);
            result.add(value);
        }
        return result;
    }

    VerifiedArtifact validateRequest(AuthorizedClientPolicy.Rule client, ModelRequest request) {
        if (!client.allows(request)) {
            throw new IllegalArgumentException("request exceeds client policy");
        }
        if (request.requestId == null || request.requestId.isEmpty()) {
            throw new IllegalArgumentException("request ID is required");
        }
        if (!SessionDeadlinePolicy.validAt(
                request.capability,
                request.deadlineElapsedRealtimeMillis,
                SystemClock.elapsedRealtime())) {
            throw new IllegalArgumentException(
                    "request deadline is expired, too distant, or invalid for its capability");
        }
        if (callActive && "media_background".equals(request.workload)) {
            throw new IllegalArgumentException("media inference is blocked during a call");
        }
        RuntimeCandidatePolicy.Choice choice = RuntimeCandidatePolicy.request(
                selectedArtifacts.values(),
                request.capability,
                request.language,
                runtimes::supports);
        if (choice != null) {
            return choice.artifact;
        }
        throw new IllegalArgumentException("no selected artifact supports the request");
    }

    boolean runtimeAvailable(VerifiedArtifact artifact) {
        return runtimes.supports(artifact);
    }

    RuntimeRegistry runtimes() {
        return runtimes;
    }

    void close() {
        runtimes.close();
    }

    void requireCallStateController(AuthorizedClientPolicy.Rule client) {
        if (!client.canControlCallState) {
            throw new SecurityException("client cannot control call-active state");
        }
    }

    void setCallActive(boolean active) {
        callActive = active;
    }

    private static long totalRamMb(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) {
            return 0L;
        }
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        return memory.totalMem / BYTES_PER_MIB;
    }
}
