package com.aios.modelbroker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MemoryTrimPolicyTest {
    @Test
    public void runningPressureAndLegacyBackgroundPressurePreemptMedia() {
        assertTrue(MemoryTrimPolicy.shouldPreemptBackground(10));
        assertTrue(MemoryTrimPolicy.shouldPreemptBackground(15));
        assertTrue(MemoryTrimPolicy.shouldPreemptBackground(40));
        assertTrue(MemoryTrimPolicy.shouldPreemptBackground(80));
    }

    @Test
    public void uiHiddenIsNotMistakenForIncreasingMemorySeverity() {
        assertFalse(MemoryTrimPolicy.shouldPreemptBackground(5));
        assertFalse(MemoryTrimPolicy.shouldPreemptBackground(20));
    }
}
