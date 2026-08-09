package com.aios.model;

parcelable InferenceResult {
    String requestId;
    String capability;
    String modelId;
    String modelDigest;
    String language;
    String outputJson;
    long elapsedMillis;
}
