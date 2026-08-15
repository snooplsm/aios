package com.aios.runtime.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeThermalTrimPolicyTest {
    @Test
    public void severeThroughShutdownReleaseIdleModels() {
        assertTrue(RuntimeThermalTrimPolicy.isThermalPressure(3));
        assertTrue(RuntimeThermalTrimPolicy.isThermalPressure(4));
        assertTrue(RuntimeThermalTrimPolicy.isThermalPressure(5));
        assertTrue(RuntimeThermalTrimPolicy.isThermalPressure(6));
    }

    @Test
    public void normalThroughModerateKeepWarmModels() {
        assertFalse(RuntimeThermalTrimPolicy.isThermalPressure(-1));
        assertFalse(RuntimeThermalTrimPolicy.isThermalPressure(0));
        assertFalse(RuntimeThermalTrimPolicy.isThermalPressure(1));
        assertFalse(RuntimeThermalTrimPolicy.isThermalPressure(2));
        assertFalse(RuntimeThermalTrimPolicy.isThermalPressure(7));
    }
}
