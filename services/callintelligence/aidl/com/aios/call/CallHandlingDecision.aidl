package com.aios.call;

parcelable CallHandlingDecision {
    const int ACTION_RING_OWNER = 0;
    const int ACTION_ANSWER_WITH_AI = 1;
    const int ACTION_RING_THEN_AI = 2;
    const int ACTION_BYPASS_AI = 3;

    int action;
    long answerDelayMillis;
    boolean aiMayAnswer;
    boolean processingAllowed;
    String reason;
}
