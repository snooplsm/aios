package com.aios.contextintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QuantizedEmbeddingTest {
    @Test
    public void quantizedVectorPreservesDirection() {
        float[] vector = vector(1.0f, 0.5f, -0.25f);
        QuantizedEmbedding embedding = QuantizedEmbedding.quantize(vector);

        assertEquals(QuantizedEmbedding.DIMENSIONS, embedding.values().length);
        assertTrue(embedding.cosine(vector) > 0.999f);

        float[] opposite = vector(-1.0f, -0.5f, 0.25f);
        assertTrue(embedding.cosine(opposite) < -0.999f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongDimensions() {
        QuantizedEmbedding.quantize(new float[128]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteValues() {
        float[] vector = vector(1.0f, 0.0f, 0.0f);
        vector[20] = Float.NaN;
        QuantizedEmbedding.quantize(vector);
    }

    private static float[] vector(float first, float second, float third) {
        float[] result = new float[QuantizedEmbedding.DIMENSIONS];
        result[0] = first;
        result[1] = second;
        result[2] = third;
        return result;
    }
}
