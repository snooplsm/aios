package com.aios.model;

parcelable ModelRequest {
    String requestId;
    String capability;
    String workload;
    String language;
    int maxOutputTokens;
    /**
     * Absolute elapsed-realtime terminal deadline. Finite requests may be no
     * more than five minutes ahead. Long.MAX_VALUE is reserved for
     * lifecycle-bound streaming_asr sessions, which end through pipe EOF, explicit
     * cancellation, callback death, or broker priority policy.
     */
    long deadlineElapsedRealtimeMillis;
    boolean allowFallback;
}
