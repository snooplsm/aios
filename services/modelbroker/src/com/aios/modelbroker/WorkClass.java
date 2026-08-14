package com.aios.modelbroker;

/** Server-derived priority; larger values preempt smaller values. */
enum WorkClass {
    MEDIA_BACKGROUND(0),
    CALL_BACKGROUND(1),
    CALL_AGENT(2),
    CALL_TX(3),
    CALL_RX(4);

    final int priority;

    WorkClass(int priority) {
        this.priority = priority;
    }

    static WorkClass fromAuthorizedWorkload(String workload) {
        switch (workload) {
            case "call_rx":
                return CALL_RX;
            case "call_tx":
                return CALL_TX;
            case "call_agent":
                return CALL_AGENT;
            case "call_background":
                return CALL_BACKGROUND;
            case "media_background":
            default:
                return MEDIA_BACKGROUND;
        }
    }
}
