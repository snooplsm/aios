package com.aios.call;

parcelable CallAssistantPolicy {
    String answerMode;
    String answerDelayMode;
    long missedDelayMillis;
    boolean processingEnabled;

    // Read-only service capability fields; ignored on update.
    boolean automaticAnswerAvailable;
    String automaticAnswerUnavailableReason;
}
