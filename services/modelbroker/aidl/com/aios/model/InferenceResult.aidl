package com.aios.model;

parcelable InferenceResult {
    String requestId;
    String capability;
    String modelId;
    String modelDigest;
    String language;
    String outputJson;
    long elapsedMillis;
    /** Exactly 256 finite, L2-normalized values for text_embedding; absent otherwise. */
    float[] embedding;
}
