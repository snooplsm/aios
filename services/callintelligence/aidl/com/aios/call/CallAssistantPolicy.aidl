package com.aios.call;

parcelable CallAssistantPolicy {
    String answerMode;
    String answerDelayMode;
    long missedDelayMillis;
    boolean processingEnabled;
    boolean callerHistoryEnabled;
    boolean messageHistoryEnabled;
    boolean callHistoryEnabled;
    boolean photoHistoryEnabled;

    // Read-only service capability fields; ignored on update.
    boolean automaticAnswerAvailable;
    String automaticAnswerUnavailableReason;
}
