package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallStatusLogPolicyTest {
    @Test
    public void markerNeverContainsCallIdentity() {
        String marker = CallStatusLogPolicy.format(
                "opaque-call-id-123", 6, "receptionist_thinking");

        assertEquals(
                "STATUS scope=call code=6 detail=receptionist_thinking", marker);
        assertFalse(marker.contains("opaque-call-id-123"));
    }

    @Test
    public void availabilityAndMissingIdentityHaveExplicitScopes() {
        assertEquals(
                "STATUS scope=availability code=3 detail=streaming_asr_ready",
                CallStatusLogPolicy.format("availability", 3, "streaming_asr_ready"));
        assertEquals(
                "STATUS scope=none code=-1 detail=capture_unavailable",
                CallStatusLogPolicy.format(null, -1, "capture_unavailable"));
    }

    @Test
    public void unexpectedOrContentBearingDetailFailsClosed() {
        String marker = CallStatusLogPolicy.format(
                "call", -2, "caller said my number is 555-0100");

        assertTrue(marker.endsWith("detail=invalid_detail"));
        assertFalse(marker.contains("555"));
        assertTrue(CallStatusLogPolicy.format("call", 1, "x".repeat(161))
                .endsWith("detail=invalid_detail"));
    }
}
