package com.aios.callintelligence;

import java.util.regex.Pattern;

/** Produces content-free status markers for physical call timing diagnostics. */
final class CallStatusLogPolicy {
    private static final Pattern DETAIL = Pattern.compile("[a-z0-9_.:=-]{1,160}");

    private CallStatusLogPolicy() {}

    static String format(String callId, int status, String detail) {
        String scope;
        if ("availability".equals(callId)) {
            scope = "availability";
        } else if (callId == null || callId.isBlank()) {
            scope = "none";
        } else {
            scope = "call";
        }
        String safeDetail = detail != null && DETAIL.matcher(detail).matches()
                ? detail : "invalid_detail";
        return "STATUS scope=" + scope + " code=" + status + " detail=" + safeDetail;
    }
}
