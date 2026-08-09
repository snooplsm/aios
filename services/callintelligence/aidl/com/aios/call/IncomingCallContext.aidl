package com.aios.call;

parcelable IncomingCallContext {
    String callId;
    String normalizedAddressHash;
    boolean knownContact;
    boolean emergency;
    boolean emergencyCallbackMode;
    long ringingSinceElapsedRealtimeMillis;
}
