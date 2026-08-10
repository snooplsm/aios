package com.aios.media;

/** One bounded subtitle cue from an AIOS-enhanced MP4. */
parcelable EnhancedVideoCue {
    int sequence;
    String language;
    long startUs;
    long endUs;
    String text;
    float confidence;
}
