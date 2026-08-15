package com.aios.contextintelligence;

/** Internal bounded document handed to the asynchronous embedding client. */
final class EmbeddingWorkItem {
    final String sourceType;
    final String sourceId;
    final long revision;
    final String text;

    EmbeddingWorkItem(String sourceType, String sourceId, long revision, String text) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.revision = revision;
        this.text = text;
    }
}
