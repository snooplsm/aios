package com.aios.phone.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceGenerationRevisionGateTest {
    @Test
    fun rejectsInvalidDuplicateAndOlderWireRevisions() {
        val gate = ServiceGenerationRevisionGate()

        assertNull(gate.accept(0L))
        assertNull(gate.accept(-1L))
        assertEquals(1L, gate.accept(2L))
        assertNull(gate.accept(2L))
        assertNull(gate.accept(1L))
        assertEquals(2L, gate.accept(4L))
    }

    @Test
    fun newServiceGenerationKeepsVisibleRevisionMonotonic() {
        val gate = ServiceGenerationRevisionGate()

        assertEquals(1L, gate.accept(10L))
        assertEquals(2L, gate.accept(11L))
        gate.nextGeneration()

        assertEquals(3L, gate.accept(1L))
        assertNull(gate.accept(1L))
        assertEquals(4L, gate.accept(2L))
    }

    @Test
    fun repeatedGenerationChangeDoesNotPublishByItself() {
        val gate = ServiceGenerationRevisionGate()

        gate.nextGeneration()
        gate.nextGeneration()

        assertEquals(1L, gate.accept(1L))
    }
}
