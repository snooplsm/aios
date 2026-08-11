package com.aios.modelbroker;

/** Immutable active-session limits enforced before a runtime provider is opened. */
final class SessionCapacityPolicy {
    final int globalSessionCapacity;
    final int callAsrStreamCapacity;
    final int callAgentCapacity;

    SessionCapacityPolicy(
            int globalSessionCapacity,
            int callAsrStreamCapacity,
            int callAgentCapacity) {
        if (globalSessionCapacity <= 0
                || callAsrStreamCapacity <= 0
                || callAgentCapacity <= 0) {
            throw new IllegalArgumentException("session capacities must be positive");
        }
        if (callAsrStreamCapacity > globalSessionCapacity
                || callAgentCapacity > globalSessionCapacity) {
            throw new IllegalArgumentException(
                    "work-class capacity cannot exceed global capacity");
        }
        this.globalSessionCapacity = globalSessionCapacity;
        this.callAsrStreamCapacity = callAsrStreamCapacity;
        this.callAgentCapacity = callAgentCapacity;
    }

    int activeLimit(WorkClass workClass) {
        switch (workClass) {
            case CALL_RX:
            case CALL_TX:
                return callAsrStreamCapacity;
            case CALL_AGENT:
                return callAgentCapacity;
            case MEDIA_BACKGROUND:
            default:
                return globalSessionCapacity;
        }
    }

    boolean sharesActivePool(WorkClass first, WorkClass second) {
        return (isCallAsr(first) && isCallAsr(second)) || first == second;
    }

    private static boolean isCallAsr(WorkClass workClass) {
        return workClass == WorkClass.CALL_RX || workClass == WorkClass.CALL_TX;
    }
}
