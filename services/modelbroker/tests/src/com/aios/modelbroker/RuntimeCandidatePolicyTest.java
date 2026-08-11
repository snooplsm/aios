package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public final class RuntimeCandidatePolicyTest {
    private static final VerifiedArtifact PRIMARY = artifact(
            "primary", List.of("text_generation", "call_classification"),
            List.of("en"));
    private static final VerifiedArtifact FALLBACK = artifact(
            "fallback", List.of("text_generation", "call_classification"),
            List.of("en", "es"));

    @Test
    public void readyPrimaryWinsForCapabilitiesAndRequests() {
        List<VerifiedArtifact> candidates = List.of(PRIMARY, FALLBACK);
        Map<String, RuntimeCandidatePolicy.Choice> capabilities =
                RuntimeCandidatePolicy.capabilities(
                        candidates, Set.of("text_generation"), item -> true);

        assertEquals("primary", capabilities.get("text_generation").artifact.modelId);
        assertTrue(capabilities.get("text_generation").available);
        assertNull(capabilities.get("call_classification"));
        assertEquals("primary", RuntimeCandidatePolicy.request(
                candidates, "text_generation", "en", true,
                item -> true).artifact.modelId);
    }

    @Test
    public void readyFallbackReplacesUnavailablePrimary() {
        List<VerifiedArtifact> candidates = List.of(PRIMARY, FALLBACK);
        Map<String, RuntimeCandidatePolicy.Choice> capabilities =
                RuntimeCandidatePolicy.capabilities(
                        candidates,
                        Set.of("text_generation", "call_classification"),
                        item -> "fallback".equals(item.modelId));

        assertEquals("fallback", capabilities.get("text_generation").artifact.modelId);
        assertTrue(capabilities.get("text_generation").available);
        assertEquals("fallback", RuntimeCandidatePolicy.request(
                candidates,
                "text_generation",
                "en",
                true,
                item -> "fallback".equals(item.modelId)).artifact.modelId);
    }

    @Test
    public void languageAndNoRuntimeCasesRemainFailClosed() {
        List<VerifiedArtifact> candidates = List.of(PRIMARY, FALLBACK);
        RuntimeCandidatePolicy.Choice spanish = RuntimeCandidatePolicy.request(
                candidates, "text_generation", "es", true, item -> true);
        RuntimeCandidatePolicy.Choice unavailable = RuntimeCandidatePolicy.request(
                candidates, "text_generation", "en", true, item -> false);
        RuntimeCandidatePolicy.Choice unavailableCapability =
                RuntimeCandidatePolicy.capabilities(
                        candidates, Set.of("text_generation"), item -> false)
                        .get("text_generation");

        assertEquals("fallback", spanish.artifact.modelId);
        assertEquals("primary", unavailable.artifact.modelId);
        assertFalse(unavailable.available);
        assertEquals("primary", unavailableCapability.artifact.modelId);
        assertFalse(unavailableCapability.available);
        assertNull(RuntimeCandidatePolicy.request(
                candidates, "image_understanding", "en", true, item -> true));
    }

    @Test
    public void noFallbackBindsRequestToPrimaryEvenWhenFallbackIsReady() {
        List<VerifiedArtifact> candidates = List.of(PRIMARY, FALLBACK);
        RuntimeCandidatePolicy.Choice exact = RuntimeCandidatePolicy.request(
                candidates,
                "text_generation",
                "en",
                false,
                item -> "fallback".equals(item.modelId));

        assertEquals("primary", exact.artifact.modelId);
        assertFalse(exact.available);
        assertEquals(List.of(PRIMARY), RuntimeCandidatePolicy.requestCandidates(
                candidates, "text_generation", "en", false));
    }

    @Test
    public void fallbackOptInCarriesCompleteOrderedActivationChain() {
        assertEquals(List.of(PRIMARY, FALLBACK),
                RuntimeCandidatePolicy.requestCandidates(
                        List.of(PRIMARY, FALLBACK),
                        "call_classification",
                        "en",
                        true));
        assertEquals(List.of(FALLBACK), RuntimeCandidatePolicy.requestCandidates(
                List.of(PRIMARY, FALLBACK), "text_generation", "es", true));
    }

    private static VerifiedArtifact artifact(
            String id, List<String> capabilities, List<String> languages) {
        return new VerifiedArtifact(
                id,
                new File(id + ".bin"),
                "0".repeat(64),
                1L,
                "test-runtime",
                "cpu",
                capabilities,
                languages);
    }
}
