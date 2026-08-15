package com.aios.contextintelligence;

import java.util.regex.Pattern;

/** Pins stored vectors to one exact model/tokenizer/preprocessing bundle. */
final class EmbeddingModelIdentity {
    private static final Pattern MODEL_ID = Pattern.compile(
            "[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private EmbeddingModelIdentity() {}

    static void validate(String modelId, String modelBundleSha256) {
        if (modelId == null || !MODEL_ID.matcher(modelId).matches()
                || modelBundleSha256 == null
                || !SHA256.matcher(modelBundleSha256).matches()) {
            throw new IllegalArgumentException("invalid embedding model identity");
        }
    }
}
