package com.aios.modelbroker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Selects only catalog models allowed by measured total memory. */
final class CatalogPolicy {
    private static final class Model {
        final String id;
        final String runtime;
        final Set<String> allowedBackends;
        final Set<String> capabilities;
        final Set<String> languages;

        Model(
                String id,
                String runtime,
                Set<String> allowedBackends,
                Set<String> capabilities,
                Set<String> languages) {
            this.id = id;
            this.runtime = runtime;
            this.allowedBackends = Set.copyOf(allowedBackends);
            this.capabilities = Set.copyOf(capabilities);
            this.languages = Set.copyOf(languages);
        }
    }

    private final Map<String, Model> models;
    private final List<CatalogTierPlanner.Tier> tiers;

    private CatalogPolicy(
            Map<String, Model> models, List<CatalogTierPlanner.Tier> tiers) {
        this.models = Collections.unmodifiableMap(models);
        this.tiers = List.copyOf(tiers);
    }

    static CatalogPolicy load(File path) throws IOException {
        try {
            JSONObject root = new JSONObject(PolicyFileReader.readUtf8(path));
            if (root.getInt("schema_version") != 1) {
                throw new IOException("unsupported model catalog schema");
            }
            JSONObject memoryPolicy = root.getJSONObject("memory_policy");
            if (!"adaptive_system_pressure".equals(memoryPolicy.getString("mode"))
                    || !memoryPolicy.isNull("fixed_model_limit_mb")
                    || !memoryPolicy.getBoolean("prefer_quality_when_headroom_available")
                    || !memoryPolicy.getBoolean("preempt_background_during_calls")
                    || !memoryPolicy.getBoolean("release_idle_models_on_trim")) {
                throw new IOException("unsupported model memory policy");
            }
            Map<String, Model> models = new HashMap<>();
            JSONArray modelValues = root.getJSONArray("models");
            for (int index = 0; index < modelValues.length(); index++) {
                JSONObject value = modelValues.getJSONObject(index);
                Model model = new Model(
                        value.getString("id"),
                        value.getString("runtime"),
                        strings(value.getJSONArray("allowed_backends")),
                        strings(value.getJSONArray("capabilities")),
                        strings(value.getJSONArray("languages")));
                if (models.put(model.id, model) != null) {
                    throw new IOException("duplicate catalog model: " + model.id);
                }
            }
            List<CatalogTierPlanner.Tier> tiers = new ArrayList<>();
            JSONArray tierValues = root.getJSONArray("tiers");
            for (int index = 0; index < tierValues.length(); index++) {
                JSONObject value = tierValues.getJSONObject(index);
                Set<String> ids = new LinkedHashSet<>();
                ids.add(value.getString("text_model"));
                ids.add(value.getString("media_model"));
                ids.add(value.getString("tts_model"));
                ids.addAll(strings(value.getJSONArray("asr_candidates")));
                tiers.add(new CatalogTierPlanner.Tier(
                        value.getString("id"), value.getLong("min_total_ram_mb"),
                        new ArrayList<>(ids),
                        value.has("fallback_tier")
                                ? value.getString("fallback_tier") : null));
            }
            try {
                CatalogTierPlanner.validate(tiers);
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid catalog tier fallback plan", error);
            }
            return new CatalogPolicy(models, tiers);
        } catch (JSONException error) {
            throw new IOException("cannot parse model catalog", error);
        }
    }

    Map<String, VerifiedArtifact> selectForMemory(
            Map<String, VerifiedArtifact> verified, long totalRamMb) {
        Map<String, VerifiedArtifact> result = new LinkedHashMap<>();
        for (String modelId : CatalogTierPlanner.candidates(tiers, totalRamMb)) {
            Model expected = models.get(modelId);
            VerifiedArtifact artifact = verified.get(modelId);
            if (expected == null || artifact == null) {
                continue;
            }
            if (!expected.runtime.equals(artifact.runtime)
                    || !expected.allowedBackends.contains(artifact.backend)
                    || !expected.capabilities.equals(new HashSet<>(artifact.capabilities))
                    || !expected.languages.equals(new HashSet<>(artifact.languages))) {
                continue;
            }
            result.put(modelId, artifact);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> strings(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            if (!result.add(values.getString(index))) {
                throw new JSONException("duplicate catalog string");
            }
        }
        return result;
    }
}
