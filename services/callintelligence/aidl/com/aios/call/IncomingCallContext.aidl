package com.aios.call;

parcelable IncomingCallContext {
    String callId;
    String normalizedAddressHash;
    boolean knownContact;
    boolean emergency;
    boolean emergencyCallbackMode;
    long ringingSinceElapsedRealtimeMillis;
    /** Optional compatibility-tail fields; present only during authorized processing. */
    String transientAddress;
    String countryIso;
}
