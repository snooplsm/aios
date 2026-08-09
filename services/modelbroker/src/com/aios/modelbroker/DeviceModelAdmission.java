package com.aios.modelbroker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** AVB-protected, evidence-backed model admission for one measured device profile. */
final class DeviceModelAdmission {
    private static final int MAX_POLICY_BYTES = 2 * 1024 * 1024;
    private static final String STATUS_PENDING = "benchmark_pending";
    private static final String STATUS_SUPPORTED = "supported";

    static final class Selection {
        final String profileId;
        final boolean researchMode;
        final Map<String, VerifiedArtifact> artifacts;

        Selection(
                String profileId,
                boolean researchMode,
                Map<String, VerifiedArtifact> artifacts) {
            this.profileId = profileId;
            this.researchMode = researchMode;
            this.artifacts = Collections.unmodifiableMap(artifacts);
        }
    }

    private static final class Admission {
        final String modelId;
        final String backend;
        final String artifactSha256;
        final String evidenceSha256;

        Admission(
                String modelId,
                String backend,
                String artifactSha256,
                String evidenceSha256) {
            this.modelId = modelId;
            this.backend = backend;
            this.artifactSha256 = artifactSha256;
            this.evidenceSha256 = evidenceSha256;
        }

        boolean matches(VerifiedArtifact artifact) {
            return modelId.equals(artifact.modelId)
                    && backend.equals(artifact.backend)
                    && artifactSha256.equals(artifact.sha256);
        }
    }

    private static final class Profile {
        final String id;
        final Set<String> devices;
        final long minTotalRamMb;
        final long maxTotalRamMb;
        final String status;
        final Set<String> researchCandidates;
        final Map<String, Admission> admissions;

        Profile(
                String id,
                Set<String> devices,
                long minTotalRamMb,
                long maxTotalRamMb,
                String status,
                Set<String> researchCandidates,
                Map<String, Admission> admissions) {
            this.id = id;
            this.devices = Set.copyOf(devices);
            this.minTotalRamMb = minTotalRamMb;
            this.maxTotalRamMb = maxTotalRamMb;
            this.status = status;
            this.researchCandidates = Set.copyOf(researchCandidates);
            this.admissions = Map.copyOf(admissions);
        }

        boolean matches(String device, long totalRamMb) {
            return devices.contains(device)
                    && totalRamMb >= minTotalRamMb
                    && totalRamMb <= maxTotalRamMb;
        }
    }

    private final Map<String, Profile> profilesByDevice;

    private DeviceModelAdmission(Map<String, Profile> profilesByDevice) {
        this.profilesByDevice = Map.copyOf(profilesByDevice);
    }

    static DeviceModelAdmission load(File path) throws IOException {
        try {
            JSONObject root = new JSONObject(readUtf8(path, MAX_POLICY_BYTES));
            if (root.getInt("schema_version") != 1
                    || !"deny".equals(root.getString("default_action"))
                    || !"known_profiles_research_candidates".equals(
                            root.getString("debug_policy"))) {
                throw new IOException("unsupported device model-admission policy");
            }
            Map<String, Profile> profilesByDevice = new HashMap<>();
            Set<String> profileIds = new HashSet<>();
            JSONArray values = root.getJSONArray("profiles");
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                String id = value.getString("id");
                if (!id.matches("[a-z0-9][a-z0-9._-]{0,127}")
                        || !profileIds.add(id)) {
                    throw new IOException("duplicate or invalid admission profile ID");
                }
                Set<String> devices = strings(value.getJSONArray("devices"));
                if (devices.isEmpty()) {
                    throw new IOException("admission profile has no device codenames");
                }
                long minimum = value.getLong("min_total_ram_mb");
                long maximum = value.getLong("max_total_ram_mb");
                if (minimum <= 0L || maximum < minimum) {
                    throw new IOException("invalid admission profile RAM range");
                }
                String status = value.getString("status");
                if (!STATUS_PENDING.equals(status) && !STATUS_SUPPORTED.equals(status)) {
                    throw new IOException("unknown admission profile status");
                }
                Set<String> research = strings(
                        value.getJSONArray("research_candidate_models"));
                Set<String> evidenceDigests = evidenceDigests(
                        value.getJSONArray("evidence"));
                Map<String, Admission> admissions = admissions(
                        value.getJSONArray("admitted_models"), evidenceDigests);
                int evidenceCount = evidenceDigests.size();
                if (STATUS_PENDING.equals(status)
                        && (!admissions.isEmpty() || evidenceCount != 0)) {
                    throw new IOException("pending profile cannot admit release models");
                }
                if (STATUS_SUPPORTED.equals(status)
                        && (admissions.isEmpty() || evidenceCount == 0)) {
                    throw new IOException("supported profile requires evidence and models");
                }
                Profile profile = new Profile(
                        id, devices, minimum, maximum, status, research, admissions);
                for (String device : devices) {
                    if (profilesByDevice.put(device, profile) != null) {
                        throw new IOException("device appears in multiple admission profiles");
                    }
                }
            }
            return new DeviceModelAdmission(profilesByDevice);
        } catch (JSONException error) {
            throw new IOException("cannot parse device model admission", error);
        }
    }

    Selection select(
            Map<String, VerifiedArtifact> tierArtifacts,
            String device,
            long totalRamMb,
            boolean debuggable) {
        Profile profile = profilesByDevice.get(device);
        if (profile == null || !profile.matches(device, totalRamMb)) {
            return new Selection("unmatched", false, Map.of());
        }
        boolean researchMode = STATUS_PENDING.equals(profile.status) && debuggable;
        Map<String, VerifiedArtifact> result = new LinkedHashMap<>();
        for (Map.Entry<String, VerifiedArtifact> item : tierArtifacts.entrySet()) {
            VerifiedArtifact artifact = item.getValue();
            if (researchMode && profile.researchCandidates.contains(artifact.modelId)) {
                result.put(item.getKey(), artifact);
                continue;
            }
            Admission admission = profile.admissions.get(artifact.modelId);
            if (STATUS_SUPPORTED.equals(profile.status)
                    && admission != null && admission.matches(artifact)) {
                result.put(item.getKey(), artifact);
            }
        }
        return new Selection(profile.id, researchMode, result);
    }

    private static Map<String, Admission> admissions(
            JSONArray values, Set<String> evidenceDigests) throws JSONException {
        Map<String, Admission> result = new HashMap<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            Admission admission = new Admission(
                    value.getString("model_id"),
                    value.getString("backend"),
                    value.getString("artifact_sha256"),
                    value.getString("evidence_sha256"));
            if (!admission.modelId.matches("[a-z0-9][a-z0-9._-]{0,127}")
                    || !admission.backend.matches("[a-z0-9][a-z0-9._-]{0,31}")
                    || !admission.artifactSha256.matches("[0-9a-f]{64}")
                    || !evidenceDigests.contains(admission.evidenceSha256)
                    || result.put(admission.modelId, admission) != null) {
                throw new JSONException("duplicate or invalid model admission");
            }
        }
        return result;
    }

    private static Set<String> evidenceDigests(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            String digest = value.getString("sha256");
            String fingerprint = value.getString("build_fingerprint_sha256");
            String suiteDigest = value.getString("suite_sha256");
            String path = value.getString("path");
            String completedAt = value.getString("completed_at");
            if (!digest.matches("[0-9a-f]{64}")
                    || !fingerprint.matches("[0-9a-f]{64}")
                    || !suiteDigest.matches("[0-9a-f]{64}")
                    || !path.matches("evidence/model-admission/[a-zA-Z0-9._/-]+\\.json")
                    || path.contains("..")
                    || !completedAt.matches("[0-9TZ:+.-]{10,64}")
                    || !result.add(digest)) {
                throw new JSONException("duplicate or invalid admission evidence");
            }
        }
        return result;
    }

    private static Set<String> strings(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            String value = values.getString(index);
            if (!value.matches("[a-z0-9][a-z0-9._-]{0,127}") || !result.add(value)) {
                throw new JSONException("duplicate or invalid policy string");
            }
        }
        return result;
    }

    private static String readUtf8(File path, int maximumBytes) throws IOException {
        if (!path.isFile() || path.length() <= 0L || path.length() > maximumBytes) {
            throw new IOException("model admission is absent, empty, or oversized");
        }
        byte[] bytes = new byte[(int) path.length()];
        int offset = 0;
        try (FileInputStream stream = new FileInputStream(path)) {
            while (offset < bytes.length) {
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("truncated model admission");
                if (count > 0) offset += count;
            }
            if (stream.read() >= 0) throw new IOException("model admission grew while reading");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
