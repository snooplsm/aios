package com.aios.modelbroker;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.aios.model.ModelCapability;
import com.aios.model.ModelRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            selected = catalog.selectForMemory(verified, totalRamMb(context));
            Log.i(TAG, "selected " + selected.size() + " verified model artifact(s)");
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
        Map<String, ModelCapability> byCapability = new LinkedHashMap<>();
        for (VerifiedArtifact artifact : selectedArtifacts.values()) {
            for (String capability : artifact.capabilities) {
                if (!client.capabilities.contains(capability)
                        || byCapability.containsKey(capability)) {
                    continue;
                }
                ModelCapability value = new ModelCapability();
                value.capability = capability;
                value.selectedModelId = artifact.modelId;
                value.languages = artifact.languages.toArray(new String[0]);
                value.available = runtimes.supports(artifact);
                value.streaming = "streaming_asr".equals(capability);
                value.maxConcurrentSessions = Math.min(client.maxSessions, 1);
                byCapability.put(capability, value);
            }
        }
        return new ArrayList<>(byCapability.values());
    }

    VerifiedArtifact validateRequest(AuthorizedClientPolicy.Rule client, ModelRequest request) {
        if (!client.allows(request)) {
            throw new IllegalArgumentException("request exceeds client policy");
        }
        if (request.requestId == null || request.requestId.isEmpty()) {
            throw new IllegalArgumentException("request ID is required");
        }
        if (request.deadlineElapsedRealtimeMillis <= SystemClock.elapsedRealtime()) {
            throw new IllegalArgumentException("request deadline has expired");
        }
        if (callActive && "media_background".equals(request.workload)) {
            throw new IllegalArgumentException("media inference is blocked during a call");
        }
        for (VerifiedArtifact artifact : selectedArtifacts.values()) {
            if (artifact.capabilities.contains(request.capability)
                    && artifact.languages.contains(request.language)) {
                return artifact;
            }
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

    void setCallActive(AuthorizedClientPolicy.Rule client, boolean active) {
        if (!client.canControlCallState) {
            throw new SecurityException("client cannot control call-active state");
        }
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
