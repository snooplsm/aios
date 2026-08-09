package com.aios.model;

parcelable ModelRequest {
    String requestId;
    String capability;
    String workload;
    String language;
    int maxOutputTokens;
    long deadlineElapsedRealtimeMillis;
    boolean allowFallback;
}
