package com.aios.modelbroker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Picks the first runtime-ready artifact without losing ordered fallback state. */
final class RuntimeCandidatePolicy {
    static final class Choice {
        final VerifiedArtifact artifact;
        final boolean available;

        Choice(VerifiedArtifact artifact, boolean available) {
            this.artifact = artifact;
            this.available = available;
        }
    }

    private RuntimeCandidatePolicy() {}

    static Map<String, Choice> capabilities(
            Iterable<VerifiedArtifact> candidates,
            Set<String> allowedCapabilities,
            Predicate<VerifiedArtifact> runtimeAvailable) {
        Map<String, Choice> result = new LinkedHashMap<>();
        for (VerifiedArtifact artifact : candidates) {
            boolean available = runtimeAvailable.test(artifact);
            for (String capability : artifact.capabilities) {
                if (!allowedCapabilities.contains(capability)) continue;
                Choice existing = result.get(capability);
                if (existing == null || (!existing.available && available)) {
                    result.put(capability, new Choice(artifact, available));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Choice request(
            Iterable<VerifiedArtifact> candidates,
            String capability,
            String language,
            Predicate<VerifiedArtifact> runtimeAvailable) {
        Choice firstUnavailable = null;
        for (VerifiedArtifact artifact : candidates) {
            if (!artifact.capabilities.contains(capability)
                    || !artifact.languages.contains(language)) {
                continue;
            }
            if (runtimeAvailable.test(artifact)) {
                return new Choice(artifact, true);
            }
            if (firstUnavailable == null) {
                firstUnavailable = new Choice(artifact, false);
            }
        }
        return firstUnavailable;
    }
}
