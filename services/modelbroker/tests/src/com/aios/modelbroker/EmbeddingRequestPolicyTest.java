package com.aios.modelbroker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmbeddingRequestPolicyTest {
    @Test
    public void embeddingRequiresTypedTaskAndNoGenerationBudget() {
        assertTrue(EmbeddingRequestPolicy.accepts("text_embedding", "query", 0));
        assertTrue(EmbeddingRequestPolicy.accepts("text_embedding", "document", 0));
        assertFalse(EmbeddingRequestPolicy.accepts("text_embedding", null, 0));
        assertFalse(EmbeddingRequestPolicy.accepts("text_embedding", "summary", 0));
        assertFalse(EmbeddingRequestPolicy.accepts("text_embedding", "query", 1));
    }

    @Test
    public void otherCapabilitiesCannotSmuggleAnEmbeddingTask() {
        assertTrue(EmbeddingRequestPolicy.accepts("text_generation", null, 128));
        assertTrue(EmbeddingRequestPolicy.accepts("streaming_asr", "", 0));
        assertFalse(EmbeddingRequestPolicy.accepts("text_generation", "query", 128));
    }

    @Test
    public void embeddingInputIsOneCompleteBoundedNonEmptyDocument() {
        assertTrue(EmbeddingRequestPolicy.acceptsTextInput(
                "text_embedding", "customer needs a Tuesday estimate", true));
        assertFalse(EmbeddingRequestPolicy.acceptsTextInput(
                "text_embedding", "", true));
        assertFalse(EmbeddingRequestPolicy.acceptsTextInput(
                "text_embedding", "   ", true));
        assertFalse(EmbeddingRequestPolicy.acceptsTextInput(
                "text_embedding", "partial", false));
        assertFalse(EmbeddingRequestPolicy.acceptsTextInput(
                "text_embedding", "x".repeat(
                        EmbeddingRequestPolicy.MAX_INPUT_CHARS + 1), true));
        assertTrue(EmbeddingRequestPolicy.acceptsTextInput(
                "text_generation", "partial", false));
    }

    @Test
    public void embeddingNeverUsesGenerationChunks() {
        assertFalse(EmbeddingRequestPolicy.permitsGenerationChunks("text_embedding"));
        assertTrue(EmbeddingRequestPolicy.permitsGenerationChunks("text_generation"));
        assertTrue(EmbeddingRequestPolicy.permitsGenerationChunks("streaming_asr"));
    }

    @Test
    public void embeddingAcceptsExactlyOneTextSubmission() {
        assertTrue(EmbeddingRequestPolicy.permitsTextSubmission(
                "text_embedding", false));
        assertFalse(EmbeddingRequestPolicy.permitsTextSubmission(
                "text_embedding", true));
        assertTrue(EmbeddingRequestPolicy.permitsTextSubmission(
                "text_generation", true));
    }
}
