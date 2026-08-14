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
    // Per-install salted address hashes. Raw phone numbers are never persisted here.
    String[] excludedCallerHistoryAddressHashes;

    // Read-only service capability fields; ignored on update.
    boolean automaticAnswerAvailable;
    String automaticAnswerUnavailableReason;
    boolean manualAiAnswerAvailable;
    String manualAiAnswerUnavailableReason;
    boolean developmentUplinkTestActive;
}
