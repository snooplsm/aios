package com.aios.modelbroker;

/** Bounds typed embedding results before they cross the provider trust boundary. */
final class EmbeddingResultPolicy {
    static final int DIMENSIONS = 256;
    private static final double MIN_SQUARED_NORM = 0.98 * 0.98;
    private static final double MAX_SQUARED_NORM = 1.02 * 1.02;

    private EmbeddingResultPolicy() {}

    static boolean accepts(String capability, float[] embedding, String outputJson) {
        if (!EmbeddingRequestPolicy.CAPABILITY.equals(capability)) {
            return embedding == null && outputJson != null;
        }
        if (outputJson != null || embedding == null || embedding.length != DIMENSIONS) {
            return false;
        }
        double squaredNorm = 0.0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) return false;
            squaredNorm += (double) value * value;
        }
        return Double.isFinite(squaredNorm)
                && squaredNorm >= MIN_SQUARED_NORM
                && squaredNorm <= MAX_SQUARED_NORM;
    }
}
