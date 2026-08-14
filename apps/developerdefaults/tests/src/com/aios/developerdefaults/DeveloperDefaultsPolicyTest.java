package com.aios.developerdefaults;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeveloperDefaultsPolicyTest {
    @Test
    public void requiresDebuggableBuildAndExplicitProductFlag() {
        assertTrue(DeveloperDefaultsPolicy.shouldApply(true, true));
        assertFalse(DeveloperDefaultsPolicy.shouldApply(false, true));
        assertFalse(DeveloperDefaultsPolicy.shouldApply(true, false));
        assertFalse(DeveloperDefaultsPolicy.shouldApply(false, false));
    }
}
