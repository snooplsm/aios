package com.aios.call;

parcelable TranscriptSegment {
    String callId;
    String direction;
    String language;
    String text;
    boolean isFinal;
    float confidence;
    long startMillis;
    long endMillis;
}
