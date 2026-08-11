package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallerHistoryPolicyTest {
    @Test
    public void admitsOnlyExplicitlyEnabledEligibleCalls() {
        assertTrue(CallerHistoryPolicy.shouldPrepare(
                true, false, false, true, "+15555550182"));
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                false, false, false, true, "+15555550182"));
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                true, false, false, false, "+15555550182"));
    }

    @Test
    public void rejectsEmergencyAndMissingAddresses() {
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                true, true, false, true, "+15555550182"));
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                true, false, true, true, "+15555550182"));
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                true, false, false, true, null));
        assertFalse(CallerHistoryPolicy.shouldPrepare(
                true, false, false, true, "  "));
    }
}
