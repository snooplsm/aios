package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SessionCapacityPolicyTest {
    @Test
    public void mapsRxAndTxToOneSharedAsrPool() {
        SessionCapacityPolicy policy = new SessionCapacityPolicy(3, 2, 1);

        assertEquals(3, policy.activeLimit(WorkClass.MEDIA_BACKGROUND));
        assertEquals(2, policy.activeLimit(WorkClass.CALL_RX));
        assertEquals(2, policy.activeLimit(WorkClass.CALL_TX));
        assertEquals(1, policy.activeLimit(WorkClass.CALL_AGENT));
        assertEquals(1, policy.activeLimit(WorkClass.CALL_BACKGROUND));
        assertTrue(policy.sharesActivePool(WorkClass.CALL_RX, WorkClass.CALL_TX));
        assertTrue(policy.sharesActivePool(
                WorkClass.CALL_AGENT, WorkClass.CALL_BACKGROUND));
        assertFalse(policy.sharesActivePool(WorkClass.CALL_RX, WorkClass.CALL_AGENT));
    }

    @Test
    public void rejectsNonPositiveOrImpossibleCapacity() {
        expectInvalid(0, 1, 1);
        expectInvalid(3, 0, 1);
        expectInvalid(3, 2, 0);
        expectInvalid(2, 3, 1);
        expectInvalid(2, 1, 3);
    }

    private static void expectInvalid(int global, int asr, int agent) {
        try {
            new SessionCapacityPolicy(global, asr, agent);
            fail("invalid capacity should throw");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
