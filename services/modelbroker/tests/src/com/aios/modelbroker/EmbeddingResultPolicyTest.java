package com.aios.modelbroker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmbeddingResultPolicyTest {
    @Test
    public void acceptsExactlyNormalizedTypedVector() {
        assertTrue(EmbeddingResultPolicy.accepts(
                "text_embedding", unitVector(), null));
    }

    @Test
    public void rejectsWrongShapeNonFiniteNormAndMixedJson() {
        assertFalse(EmbeddingResultPolicy.accepts(
                "text_embedding", new float[128], null));
        float[] nonFinite = unitVector();
        nonFinite[10] = Float.NaN;
        assertFalse(EmbeddingResultPolicy.accepts("text_embedding", nonFinite, null));
        float[] unnormalized = unitVector();
        unnormalized[0] = 0.5f;
        assertFalse(EmbeddingResultPolicy.accepts("text_embedding", unnormalized, null));
        assertFalse(EmbeddingResultPolicy.accepts(
                "text_embedding", unitVector(), "{}"));
    }

    @Test
    public void nonEmbeddingResultsRequireJsonAndNoVector() {
        assertTrue(EmbeddingResultPolicy.accepts("text_generation", null, "{}"));
        assertFalse(EmbeddingResultPolicy.accepts(
                "text_generation", unitVector(), "{}"));
        assertFalse(EmbeddingResultPolicy.accepts("text_generation", null, null));
    }

    private static float[] unitVector() {
        float[] value = new float[EmbeddingResultPolicy.DIMENSIONS];
        value[0] = 1.0f;
        return value;
    }
}
