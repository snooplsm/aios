package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaWorkPolicyTest {
    @Test
    public void isolatedPhotoMayRunWithoutExternalPower() {
        assertNull(MediaWorkPolicy.executionBlockReason(
                MediaWorkPolicy.CLASS_IMMEDIATE, false, false, false, 4));
    }

    @Test
    public void activeCallPreemptsEveryWorkClass() {
        assertEquals(
                MediaWorkPolicy.BLOCK_ACTIVE_CALL,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_IMMEDIATE, true, false, true, 100));
        assertEquals(
                MediaWorkPolicy.BLOCK_ACTIVE_CALL,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_DEFERRED, true, false, true, 100));
    }

    @Test
    public void severeThermalPressurePreemptsEveryWorkClass() {
        assertEquals(
                MediaWorkPolicy.BLOCK_THERMAL_PRESSURE,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_IMMEDIATE, false, true, true, 100));
        assertEquals(
                MediaWorkPolicy.BLOCK_THERMAL_PRESSURE,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_DEFERRED, false, true, true, 100));
    }

    @Test
    public void deferredWorkRequiresChargingAtEightyPercent() {
        assertEquals(
                MediaWorkPolicy.BLOCK_NOT_CHARGING,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_DEFERRED, false, false, false, 100));
        assertEquals(
                MediaWorkPolicy.BLOCK_BELOW_BATTERY_THRESHOLD,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_DEFERRED, false, false, true, 79));
        assertNull(MediaWorkPolicy.executionBlockReason(
                MediaWorkPolicy.CLASS_DEFERRED, false, false, true, 80));
    }

    @Test
    public void unavailableBatteryStateFailsDeferredWorkClosed() {
        assertEquals(
                MediaWorkPolicy.BLOCK_BATTERY_STATE_UNAVAILABLE,
                MediaWorkPolicy.executionBlockReason(
                        MediaWorkPolicy.CLASS_DEFERRED, false, false, true, -1));
        assertFalse(MediaWorkPolicy.deferredConstraintsSatisfied(true, 101));
    }

    @Test
    public void unknownWorkClassFailsClosed() {
        assertFalse(MediaWorkPolicy.isKnownWorkClass(9));
        assertEquals(
                MediaWorkPolicy.BLOCK_UNKNOWN_WORK_CLASS,
                MediaWorkPolicy.executionBlockReason(9, false, false, true, 100));
        assertTrue(MediaWorkPolicy.isKnownWorkClass(MediaWorkPolicy.CLASS_IMMEDIATE));
    }
}
