package com.aios.modelbroker;

/** Server-derived priority; larger values preempt smaller values. */
enum WorkClass {
    MEDIA_BACKGROUND(0),
    CALL_AGENT(1),
    CALL_TX(2),
    CALL_RX(3);

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
            case "media_background":
            default:
                return MEDIA_BACKGROUND;
        }
    }
}
