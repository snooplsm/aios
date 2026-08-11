package com.aios.modelbroker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.aios.model.IAiosModelService;
import com.aios.model.ModelRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Exercises production artifact and device admission with temporary non-model bytes. */
public final class ModelAdmissionSmokeActivity extends Activity {
    private static final String TAG = "AiosModelAdmissionSmoke";
    private static final String MODEL_ID = "fixture-text-model";
    private static final String RUNTIME = "fixture_runtime";
    private static final String BACKEND = "cpu";
    private static final String EVIDENCE_SHA = "a".repeat(64);
    private static final String SUITE_SHA = "b".repeat(64);

    private File fixtureRoot;
    private boolean bound;
    private Throwable result;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                IAiosModelService remote = IAiosModelService.Stub.asInterface(binder);
                require(remote != null, "production Model Broker AIDL was unavailable");
                boolean denied = false;
                try {
                    remote.listCapabilities();
                } catch (SecurityException expected) {
                    denied = true;
                }
                require(denied,
                        "stock install exposed a model client without /product policy");
            } catch (Throwable error) {
                result = error;
            } finally {
                finishFixture();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Normal unbinding below tears down the in-process broker.
        }

        @Override
        public void onNullBinding(ComponentName name) {
            result = new IllegalStateException("production Model Broker returned a null binding");
            finishFixture();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        fixtureRoot = new File(getNoBackupFilesDir(), "model-admission-smoke");
        try {
            require(!new File("/product/etc/aios").isDirectory(),
                    "fixture requires a stock emulator without AIOS product policy");
            require(deleteTree(fixtureRoot), "could not reset model fixture directory");
            require(new File(fixtureRoot, "models").mkdirs(),
                    "could not create model fixture directory");
            verifyTemporaryArtifactChain();
        } catch (Throwable error) {
            result = error;
            finishFixture();
            return;
        }

        Intent intent = new Intent(this, ModelBrokerService.class)
                .setAction("com.aios.model.MODEL_SERVICE");
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                result = new IllegalStateException("could not bind production Model Broker");
                finishFixture();
            }
        } catch (Throwable error) {
            result = error;
            finishFixture();
        }
    }

    private void verifyTemporaryArtifactChain() throws Exception {
        byte[] artifactBytes = "AIOS admission fixture; not model weights."
                .getBytes(StandardCharsets.UTF_8);
        File artifact = new File(fixtureRoot, "models/fixture.bin");
        writeBytes(artifact, artifactBytes);
        String digest = sha256(artifact);
        File artifactManifest = new File(fixtureRoot, "model_artifacts.json");
        writeJson(artifactManifest, artifactManifest("models/fixture.bin", digest,
                artifactBytes.length));

        Map<String, VerifiedArtifact> verified = new ArtifactVerifier(fixtureRoot).verifyAll();
        require(verified.size() == 1 && verified.containsKey(MODEL_ID),
                "matching artifact digest was not admitted");
        VerifiedArtifact accepted = verified.get(MODEL_ID);
        require(digest.equals(accepted.sha256) && artifact.getCanonicalFile().equals(
                        accepted.file.getCanonicalFile()),
                "verified artifact identity changed");

        byte[] tampered = artifactBytes.clone();
        tampered[tampered.length - 1] ^= 1;
        writeBytes(artifact, tampered);
        require(new ArtifactVerifier(fixtureRoot).verifyAll().isEmpty(),
                "same-size artifact tampering passed SHA-256 verification");
        writeBytes(artifact, artifactBytes);

        File outside = new File(fixtureRoot, "outside.bin");
        writeBytes(outside, artifactBytes);
        writeJson(artifactManifest, artifactManifest("../outside.bin", digest,
                artifactBytes.length));
        require(new ArtifactVerifier(fixtureRoot).verifyAll().isEmpty(),
                "canonical path escape passed model-directory confinement");
        writeJson(artifactManifest, artifactManifest("models/fixture.bin", digest,
                artifactBytes.length));
        verified = new ArtifactVerifier(fixtureRoot).verifyAll();

        File catalogFile = new File(fixtureRoot, "model_catalog.json");
        writeJson(catalogFile, catalog());
        Map<String, VerifiedArtifact> tier = CatalogPolicy.load(catalogFile)
                .selectForMemory(verified, 8_192L);
        require(tier.size() == 1 && tier.get(MODEL_ID).estimatedResidentMb == 64L,
                "RAM-tier catalog did not select the verified artifact");
        require(CatalogPolicy.load(catalogFile).selectForMemory(verified, 0L).isEmpty(),
                "catalog selected a model below its RAM floor");

        String fingerprint = BuildFingerprintPolicy.sha256(Build.FINGERPRINT);
        File admissionFile = new File(fixtureRoot, "model_admission.json");
        writeJson(admissionFile, supportedAdmission(digest, fingerprint));
        DeviceModelAdmission supported = DeviceModelAdmission.load(admissionFile);
        require(supported.select(tier, Build.DEVICE, 8_192L, false, fingerprint)
                        .artifacts.size() == 1,
                "matching release admission did not select the artifact");
        require(supported.select(tier, Build.DEVICE, 8_192L, false, "c".repeat(64))
                        .artifacts.isEmpty(),
                "mismatched build fingerprint reused release evidence");

        writeJson(admissionFile, pendingAdmission());
        DeviceModelAdmission pending = DeviceModelAdmission.load(admissionFile);
        require(pending.select(tier, Build.DEVICE, 8_192L, true, fingerprint)
                        .artifacts.size() == 1,
                "known-device research candidate was unavailable on a debug selection");
        require(pending.select(tier, Build.DEVICE, 8_192L, false, fingerprint)
                        .artifacts.isEmpty(),
                "pending benchmark candidate escaped into release selection");

        File clientsFile = new File(fixtureRoot, "authorized_clients.json");
        writeJson(clientsFile, authorizedClients());
        AuthorizedClientPolicy.Rule client = AuthorizedClientPolicy.load(this, clientsFile)
                .resolveUid(Process.myUid());
        require(client != null && client.maxSessions == 1
                        && client.maxOutputTokens == 256,
                "installed benchmark package did not resolve to its exact client rule");
        ModelRequest request = new ModelRequest();
        request.capability = "text_generation";
        request.workload = "call_agent";
        request.maxOutputTokens = 256;
        require(client.allows(request), "authorized model request was rejected");
        request.maxOutputTokens = 257;
        require(!client.allows(request), "client output quota was not enforced");
    }

    private static JSONObject artifactManifest(
            String relativePath, String digest, long size) throws Exception {
        JSONObject artifact = new JSONObject()
                .put("model_id", MODEL_ID)
                .put("relative_path", relativePath)
                .put("sha256", digest)
                .put("size_bytes", size)
                .put("runtime", RUNTIME)
                .put("backend", BACKEND)
                .put("capabilities", new JSONArray().put("text_generation"))
                .put("languages", new JSONArray().put("en").put("es"));
        return new JSONObject().put("schema_version", 1)
                .put("artifacts", new JSONArray().put(artifact));
    }

    private static JSONObject catalog() throws Exception {
        JSONObject memory = new JSONObject()
                .put("mode", "adaptive_system_pressure")
                .put("fixed_model_limit_mb", JSONObject.NULL)
                .put("prefer_quality_when_headroom_available", true)
                .put("preempt_background_during_calls", true)
                .put("release_idle_models_on_trim", true);
        JSONObject model = new JSONObject()
                .put("id", MODEL_ID)
                .put("runtime", RUNTIME)
                .put("allowed_backends", new JSONArray().put(BACKEND))
                .put("capabilities", new JSONArray().put("text_generation"))
                .put("languages", new JSONArray().put("en").put("es"))
                .put("estimated_resident_mb", 64L);
        JSONObject tier = new JSONObject()
                .put("id", "fixture_1mb")
                .put("min_total_ram_mb", 1L)
                .put("text_model", MODEL_ID)
                .put("media_model", MODEL_ID)
                .put("tts_model", MODEL_ID)
                .put("asr_candidates", new JSONArray());
        return new JSONObject()
                .put("schema_version", 1)
                .put("memory_policy", memory)
                .put("models", new JSONArray().put(model))
                .put("tiers", new JSONArray().put(tier));
    }

    private static JSONObject supportedAdmission(String artifactDigest, String fingerprint)
            throws Exception {
        JSONObject evidence = new JSONObject()
                .put("sha256", EVIDENCE_SHA)
                .put("build_fingerprint_sha256", fingerprint)
                .put("suite_sha256", SUITE_SHA)
                .put("path", "evidence/model-admission/fixture.json")
                .put("completed_at", "2026-08-11T00:00:00Z");
        JSONObject admission = new JSONObject()
                .put("model_id", MODEL_ID)
                .put("backend", BACKEND)
                .put("artifact_sha256", artifactDigest)
                .put("evidence_sha256", EVIDENCE_SHA);
        return admissionRoot("supported", new JSONArray(),
                new JSONArray().put(admission), new JSONArray().put(evidence));
    }

    private static JSONObject pendingAdmission() throws Exception {
        return admissionRoot("benchmark_pending", new JSONArray().put(MODEL_ID),
                new JSONArray(), new JSONArray());
    }

    private static JSONObject admissionRoot(
            String status,
            JSONArray research,
            JSONArray admitted,
            JSONArray evidence) throws Exception {
        JSONObject profile = new JSONObject()
                .put("id", "fixture_" + status)
                .put("devices", new JSONArray().put(Build.DEVICE))
                .put("min_total_ram_mb", 1L)
                .put("max_total_ram_mb", 65_536L)
                .put("status", status)
                .put("research_candidate_models", research)
                .put("admitted_models", admitted)
                .put("evidence", evidence);
        return new JSONObject()
                .put("schema_version", 1)
                .put("default_action", "deny")
                .put("debug_policy", "known_profiles_research_candidates")
                .put("profiles", new JSONArray().put(profile));
    }

    private static JSONObject authorizedClients() throws Exception {
        JSONObject client = new JSONObject()
                .put("package", "com.aios.modelbenchmark")
                .put("capabilities", new JSONArray().put("text_generation"))
                .put("workloads", new JSONArray().put("call_agent"))
                .put("max_sessions", 1)
                .put("max_output_tokens", 256)
                .put("can_control_call_state", false);
        return new JSONObject().put("schema_version", 1)
                .put("clients", new JSONArray().put(client));
    }

    private void finishFixture() {
        if (bound) {
            bound = false;
            unbindService(connection);
        }
        if (!deleteTree(fixtureRoot) && fixtureRoot != null && fixtureRoot.exists()) {
            result = new IllegalStateException("temporary model fixture survived cleanup");
        }
        if (result == null) {
            Log.i(TAG, "AIOS_MODEL_ADMISSION_SMOKE_OK");
        } else {
            Log.e(TAG, "AIOS_MODEL_ADMISSION_SMOKE_FAILED", result);
        }
        finish();
    }

    private static void writeJson(File file, JSONObject value) throws Exception {
        writeBytes(file, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(File file, byte[] value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create fixture parent");
        }
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(value);
            stream.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }
        return file.delete();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
