package com.aios.contextintelligence;

import org.junit.Test;

public final class EmbeddingModelIdentityTest {
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void acceptsPinnedLowercaseArtifactIdentity() {
        EmbeddingModelIdentity.validate("embeddinggemma-300m-q4", SHA256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnpinnedArtifact() {
        EmbeddingModelIdentity.validate("embeddinggemma-300m-q4", "latest");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPathLikeModelId() {
        EmbeddingModelIdentity.validate("../embeddinggemma", SHA256);
    }
}
