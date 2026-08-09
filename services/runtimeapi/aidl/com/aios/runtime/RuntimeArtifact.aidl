package com.aios.runtime;

/** Internal, signature-only description of a verified model artifact. */
parcelable RuntimeArtifact {
    String modelId;
    String modelPath;
    String modelDigest;
    long sizeBytes;
    String backend;
}
