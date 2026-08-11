package com.aios.contextintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ContextExpiryPolicyTest {
    @Test
    public void nonExpiringSourcesIgnoreDeadlineFields() {
        assertFalse(ContextExpiryPolicy.isExpired(
                1L, 0L, "", 0L, 0L,
                "boot:8", Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void eitherClockExpiresCallContext() {
        assertFalse(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "boot:7", 500L, 86_400_500L,
                "boot:7", 86_409_999L, 86_400_499L));
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "boot:7", 500L, 86_400_500L,
                "boot:7", 86_410_000L, 86_400_499L));
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "boot:7", 500L, 86_400_500L,
                "boot:7", 86_409_999L, 86_400_500L));
    }

    @Test
    public void wallClockRollbackCannotExtendTheMonotonicDeadline() {
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L,
                86_410_000L,
                "boot:7",
                2_000L,
                86_402_000L,
                "boot:7",
                1L,
                86_402_000L));
    }

    @Test
    public void rebootAndLegacyRowsExpireFailClosed() {
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "boot:7", 500L, 86_400_500L,
                "boot:8", 11_000L, 100L));
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "", 0L, 0L,
                "boot:8", 11_000L, 100L));
    }

    @Test
    public void malformedDeadlineFailsClosed() {
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 20_000L, "boot:7", 500L, Long.MIN_VALUE,
                "boot:7", 10_000L, 500L));
        assertTrue(ContextExpiryPolicy.isExpired(
                10_000L, 86_410_000L, "boot:7", 500L, 86_400_500L,
                "boot:7", 10_001L, 499L));
        assertFalse(ContextExpiryPolicy.isWellFormed(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
