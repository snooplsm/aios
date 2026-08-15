package com.aios.modelbroker;

/** Typed request contract separating retrieval queries from indexed documents. */
final class EmbeddingRequestPolicy {
    static final String CAPABILITY = "text_embedding";
    static final String QUERY = "query";
    static final String DOCUMENT = "document";
    static final int MAX_INPUT_CHARS = 4_096;

    private EmbeddingRequestPolicy() {}

    static boolean accepts(String capability, String embeddingTask, int maxOutputTokens) {
        if (CAPABILITY.equals(capability)) {
            return maxOutputTokens == 0
                    && (QUERY.equals(embeddingTask) || DOCUMENT.equals(embeddingTask));
        }
        return embeddingTask == null || embeddingTask.isEmpty();
    }

    static boolean acceptsTextInput(
            String capability, String text, boolean endOfInput) {
        if (!CAPABILITY.equals(capability)) {
            return true;
        }
        return endOfInput
                && text != null
                && !text.trim().isEmpty()
                && text.length() <= MAX_INPUT_CHARS;
    }

    static boolean permitsGenerationChunks(String capability) {
        return !CAPABILITY.equals(capability);
    }

    static boolean permitsTextSubmission(String capability, boolean alreadySubmitted) {
        return !CAPABILITY.equals(capability) || !alreadySubmitted;
    }
}
