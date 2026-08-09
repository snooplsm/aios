package com.aios.model;

parcelable GenerationChunk {
    long sequence;
    String text;
    String language;
    boolean isFinal;
    float confidence;
    long sourceStartMillis;
    long sourceEndMillis;
}
