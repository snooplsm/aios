package com.aios.model;

parcelable ModelCapability {
    String capability;
    String selectedModelId;
    /** SHA-256 of the exact AVB-verified model bundle selected for this client. */
    String selectedModelDigest;
    String[] languages;
    boolean available;
    boolean streaming;
    int maxConcurrentSessions;
}
