package com.aios.contextintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ContextPolicyTest {
    private static final String NUMBER = "number:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CONTACT = "contact:"
            + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    public void sourceOwnershipAndReadersAreNarrow() {
        ContextPolicy.validateWrite(
                "com.aios.messaging", ContextPolicy.SMS, "42", 1L,
                NUMBER, CONTACT, new String[]{NUMBER}, 1_000L, 0L, "See you Tuesday");
        assertTrue(ContextPolicy.canQuery("com.aios.phone"));
        assertFalse(ContextPolicy.canQuery("com.aios.mediaintelligence"));
        assertSecurity(() -> ContextPolicy.validateWrite(
                "com.aios.phone", ContextPolicy.SMS, "42", 1L,
                NUMBER, CONTACT, new String[]{NUMBER}, 1_000L, 0L, "forged message"));
    }

    @Test
    public void callArtifactsMustExpireWithin24Hours() {
        long observed = 10_000L;
        ContextPolicy.validateWrite(
                "com.aios.callintelligence", ContextPolicy.CALL_ARTIFACT, "call-1", 1L,
                NUMBER, "", new String[]{NUMBER}, observed,
                observed + ContextPolicy.CALL_ARTIFACT_TTL_MILLIS,
                "Caller asked for an estimate");
        assertIllegal(() -> ContextPolicy.validateWrite(
                "com.aios.callintelligence", ContextPolicy.CALL_ARTIFACT, "call-1", 2L,
                NUMBER, "", new String[]{NUMBER}, observed,
                observed + ContextPolicy.CALL_ARTIFACT_TTL_MILLIS + 1L,
                "Caller asked for an estimate"));
        assertIllegal(() -> ContextPolicy.validateWrite(
                "com.aios.messaging", ContextPolicy.SMS, "42", 1L,
                NUMBER, "", new String[]{NUMBER}, observed, observed + 1L, "message"));
    }

    @Test
    public void identitiesAndBoundsFailClosed() {
        assertIllegal(() -> ContextPolicy.validateIdentity(
                "number:+15551212", "", new String[]{NUMBER}));
        assertIllegal(() -> ContextPolicy.validateQuery(
                "com.aios.messaging", NUMBER, "", new String[]{NUMBER}, "x", 9, 1L));
        assertIllegal(() -> ContextPolicy.validateWrite(
                "com.aios.messaging", ContextPolicy.SMS, "42", 1L,
                NUMBER, "", new String[]{NUMBER}, 1L, 0L, "x".repeat(4_097)));
    }

    private static void assertIllegal(Runnable operation) {
        try {
            operation.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertSecurity(Runnable operation) {
        try {
            operation.run();
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // Expected.
        }
    }
}
