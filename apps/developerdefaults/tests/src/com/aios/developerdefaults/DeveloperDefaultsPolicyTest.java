package com.aios.developerdefaults;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeveloperDefaultsPolicyTest {
    @Test
    public void requiresDebuggableBuildAndExplicitProductFlag() {
        assertTrue(DeveloperDefaultsPolicy.shouldApply("eng", true));
        assertTrue(DeveloperDefaultsPolicy.shouldApply("userdebug", true));
        assertFalse(DeveloperDefaultsPolicy.shouldApply("user", true));
        assertFalse(DeveloperDefaultsPolicy.shouldApply("userdebug", false));
        assertFalse(DeveloperDefaultsPolicy.shouldApply(null, true));
    }
}
