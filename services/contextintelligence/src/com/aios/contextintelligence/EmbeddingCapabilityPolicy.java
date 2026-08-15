package com.aios.contextintelligence;

/** Pure admission policy for the one exact multilingual embedding capability. */
final class EmbeddingCapabilityPolicy {
    static final String CAPABILITY = "text_embedding";
    static final String LANGUAGE = "und";

    private EmbeddingCapabilityPolicy() {}

    static boolean accepts(
            String capability,
            boolean available,
            String[] languages,
            String modelId,
            String modelDigest) {
        if (!CAPABILITY.equals(capability) || !available || !contains(languages, LANGUAGE)) {
            return false;
        }
        try {
            EmbeddingModelIdentity.validate(modelId, modelDigest);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean contains(String[] values, String expected) {
        if (values == null) return false;
        for (String value : values) if (expected.equals(value)) return true;
        return false;
    }
}
