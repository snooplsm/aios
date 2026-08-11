package com.aios.callintelligence;

/** Separates non-terminal recovery/availability status from assistant turn completion. */
final class ReceptionistStatusPolicy {
    private ReceptionistStatusPolicy() {}

    static boolean completesAssistantOperation(String callId, String detail) {
        return callId != null
                && !"availability".equals(callId)
                && detail != null
                && !"receptionist_ready".equals(detail)
                && !"receptionist_broker_recovering".equals(detail);
    }
}
