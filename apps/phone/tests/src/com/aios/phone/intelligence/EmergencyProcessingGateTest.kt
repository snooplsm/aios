package com.aios.phone.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyProcessingGateTest {
    @Test
    fun initialTelecomSignalsProtectImmediately() {
        assertTrue(EmergencyProcessingGate(true, false).isProtected())
        assertTrue(EmergencyProcessingGate(false, true).isProtected())
        assertFalse(EmergencyProcessingGate(false, false).isProtected())
    }

    @Test
    fun emergencyNumberResultLatchesProtection() {
        val gate = EmergencyProcessingGate(false, false)
        val revision = gate.beginNumberCheck()

        assertTrue(gate.completeNumberCheck(revision, true))
        assertTrue(gate.isProtected())
    }

    @Test
    fun lateTelecomSignalInvalidatesPendingNumberResult() {
        val gate = EmergencyProcessingGate(false, false)
        val revision = gate.beginNumberCheck()

        assertTrue(gate.observeTelecom(networkIdentified = true, emergencyCallbackMode = false))
        assertFalse(gate.completeNumberCheck(revision, false))
        assertTrue(gate.isProtected())
        assertFalse(gate.observeTelecom(networkIdentified = false, emergencyCallbackMode = false))
    }
}
