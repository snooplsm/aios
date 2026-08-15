package com.aios.contextintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmbeddingCapabilityPolicyTest {
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void acceptsOnlyAvailableUndCapabilityWithExactIdentity() {
        assertTrue(EmbeddingCapabilityPolicy.accepts(
                "text_embedding", true, new String[]{"en", "es", "und"},
                "embeddinggemma-300m-q4", SHA256));
        assertFalse(EmbeddingCapabilityPolicy.accepts(
                "text_embedding", false, new String[]{"und"},
                "embeddinggemma-300m-q4", SHA256));
        assertFalse(EmbeddingCapabilityPolicy.accepts(
                "text_embedding", true, new String[]{"en", "es"},
                "embeddinggemma-300m-q4", SHA256));
        assertFalse(EmbeddingCapabilityPolicy.accepts(
                "text_generation", true, new String[]{"und"},
                "embeddinggemma-300m-q4", SHA256));
        assertFalse(EmbeddingCapabilityPolicy.accepts(
                "text_embedding", true, new String[]{"und"},
                "embeddinggemma-300m-q4", "latest"));
    }
}
