package com.aios.developerdefaults;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DebugProvisioningPolicyTest {
    @Test
    public void requiresEveryDebugAndCredentialGate() {
        assertTrue(DebugProvisioningPolicy.shouldApply(true, true, "test", "12345678"));
        assertFalse(DebugProvisioningPolicy.shouldApply(false, true, "test", "12345678"));
        assertFalse(DebugProvisioningPolicy.shouldApply(true, false, "test", "12345678"));
        assertFalse(DebugProvisioningPolicy.shouldApply(true, true, "", "12345678"));
        assertFalse(DebugProvisioningPolicy.shouldApply(true, true, "test", "short"));
    }
}
