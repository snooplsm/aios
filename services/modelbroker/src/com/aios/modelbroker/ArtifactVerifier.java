package com.aios.modelbroker;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Recomputes artifact identity before any runtime can map model weights. */
final class ArtifactVerifier {
    private static final String TAG = "AiosArtifactVerifier";
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BUNDLE_DESCRIPTOR_BYTES = 1024 * 1024;

    private final File configurationDirectory;
    private final File modelDirectory;
    private final File artifactManifest;

    ArtifactVerifier(File configurationDirectory) throws IOException {
        this.configurationDirectory = configurationDirectory.getCanonicalFile();
        modelDirectory = new File(this.configurationDirectory, "models").getCanonicalFile();
        artifactManifest = new File(
                this.configurationDirectory, "model_artifacts.json").getCanonicalFile();
    }

    Map<String, VerifiedArtifact> verifyAll() {
        if (!artifactManifest.isFile()) {
            Log.i(TAG, "no model artifact manifest; broker remains model-free");
            return Collections.emptyMap();
        }
        try {
            JSONObject root = new JSONObject(readUtf8(
                    artifactManifest, MAX_ARTIFACT_MANIFEST_BYTES));
            if (root.getInt("schema_version") != 1) {
                throw new IOException("unsupported artifact manifest schema");
            }
            JSONArray artifacts = root.getJSONArray("artifacts");
            Map<String, VerifiedArtifact> verified = new HashMap<>();
            Map<String, String> verifiedDigests = new HashMap<>();
            for (int index = 0; index < artifacts.length(); index++) {
                VerifiedArtifact artifact = verifyOne(
                        artifacts.getJSONObject(index), verifiedDigests);
                if (verified.put(artifact.modelId, artifact) != null) {
                    throw new IOException("duplicate artifact ID: " + artifact.modelId);
                }
            }
            return Collections.unmodifiableMap(verified);
        } catch (IOException | JSONException error) {
            Log.e(TAG, "artifact verification failed; no models will activate", error);
            return Collections.emptyMap();
        }
    }

    private VerifiedArtifact verifyOne(
            JSONObject value, Map<String, String> verifiedDigests)
            throws IOException, JSONException {
        String modelId = value.getString("model_id");
        if (!MODEL_ID.matcher(modelId).matches()) {
            throw new IOException("invalid model ID");
        }
        File artifact = verifyFile(modelId, value, verifiedDigests);
        if (value.has("bundle_members")) {
            verifyBundle(modelId, artifact, value, verifiedDigests);
        }
        return new VerifiedArtifact(
                modelId,
                artifact,
                value.getString("sha256"),
                value.getLong("size_bytes"),
                value.getString("runtime"),
                value.getString("backend"),
                strings(value.getJSONArray("capabilities")),
                strings(value.getJSONArray("languages")));
    }

    private File verifyFile(
            String owner, JSONObject value, Map<String, String> verifiedDigests)
            throws IOException, JSONException {
        String relativePath = value.getString("relative_path");
        File artifact = new File(configurationDirectory, relativePath).getCanonicalFile();
        String modelPrefix = modelDirectory.getPath() + File.separator;
        if (!artifact.getPath().startsWith(modelPrefix) || !artifact.isFile()) {
            throw new IOException(owner + ": artifact escapes model directory or is absent");
        }
        long expectedSize = value.getLong("size_bytes");
        if (expectedSize <= 0 || artifact.length() != expectedSize) {
            throw new IOException(owner + ": artifact size mismatch");
        }
        String expectedDigest = value.getString("sha256");
        if (!DIGEST.matcher(expectedDigest).matches()) {
            throw new IOException(owner + ": malformed digest");
        }
        String actualDigest = verifiedDigests.get(artifact.getPath());
        if (actualDigest == null) {
            actualDigest = sha256(artifact);
            verifiedDigests.put(artifact.getPath(), actualDigest);
        }
        if (!MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.US_ASCII),
                actualDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException(owner + ": artifact digest mismatch");
        }
        return artifact;
    }

    private void verifyBundle(
            String modelId,
            File descriptor,
            JSONObject outer,
            Map<String, String> verifiedDigests)
            throws IOException, JSONException {
        JSONObject inner = new JSONObject(readUtf8(
                descriptor, MAX_BUNDLE_DESCRIPTOR_BYTES));
        if (inner.getInt("schema_version") != 1
                || !modelId.equals(inner.getString("model_id"))
                || !outer.getString("source_archive_sha256").equals(
                        inner.getString("source_archive_sha256"))) {
            throw new IOException(modelId + ": bundle descriptor identity mismatch");
        }
        JSONArray expected = outer.getJSONArray("bundle_members");
        JSONArray described = inner.getJSONArray("members");
        if (expected.length() == 0 || expected.length() != described.length()) {
            throw new IOException(modelId + ": bundle member count mismatch");
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < expected.length(); index++) {
            JSONObject locked = expected.getJSONObject(index);
            JSONObject recorded = described.getJSONObject(index);
            String name = locked.getString("name");
            if (!names.add(name)
                    || !name.equals(recorded.getString("name"))
                    || !locked.getString("relative_path").equals(
                            recorded.getString("relative_path"))
                    || locked.getLong("size_bytes") != recorded.getLong("size_bytes")
                    || !locked.getString("sha256").equals(recorded.getString("sha256"))) {
                throw new IOException(modelId + ": bundle descriptor member mismatch");
            }
            verifyFile(modelId + "/" + name, locked, verifiedDigests);
        }
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }

    private static String sha256(File path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream stream = new BufferedInputStream(
                    new FileInputStream(path), BUFFER_BYTES)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String readUtf8(File path, int maximumBytes) throws IOException {
        long length = path.length();
        if (length < 0 || length > maximumBytes) {
            throw new IOException(path + ": JSON input exceeds its bound");
        }
        try (FileInputStream stream = new FileInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) length)) {
            byte[] buffer = new byte[Math.min(BUFFER_BYTES, maximumBytes)];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maximumBytes) {
                    throw new IOException(path + ": JSON input grew beyond its bound");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
