package com.aios.runtime.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeMemoryTrimPolicyTest {
    @Test
    public void runningAndCachedProcessPressureReleaseIdleModels() {
        assertTrue(RuntimeMemoryTrimPolicy.isMemoryPressure(10));
        assertTrue(RuntimeMemoryTrimPolicy.isMemoryPressure(15));
        assertTrue(RuntimeMemoryTrimPolicy.isMemoryPressure(40));
        assertTrue(RuntimeMemoryTrimPolicy.isMemoryPressure(60));
        assertTrue(RuntimeMemoryTrimPolicy.isMemoryPressure(80));
    }

    @Test
    public void moderateRunningAndUiHiddenCallbacksKeepWarmModels() {
        assertFalse(RuntimeMemoryTrimPolicy.isMemoryPressure(5));
        assertFalse(RuntimeMemoryTrimPolicy.isMemoryPressure(20));
    }
}
