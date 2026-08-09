package com.aios.model;

parcelable ModelCapability {
    String capability;
    String selectedModelId;
    String[] languages;
    boolean available;
    boolean streaming;
    int maxConcurrentSessions;
}
