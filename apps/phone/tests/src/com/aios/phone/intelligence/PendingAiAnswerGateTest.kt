package com.aios.phone.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAiAnswerGateTest {
    @Test
    fun reservationCanBeConsumedOnlyOnce() {
        val gate = PendingAiAnswerGate()
        val reservation = gate.arm("call-one")

        assertTrue(gate.consume("call-one", reservation))
        assertFalse(gate.consume("call-one", reservation))
    }

    @Test
    fun ownerCancellationRejectsAlreadyQueuedCallback() {
        val gate = PendingAiAnswerGate()
        val reservation = gate.arm("call-one")

        assertTrue(gate.cancel("call-one"))
        assertFalse(gate.consume("call-one", reservation))
    }

    @Test
    fun rearmInvalidatesOlderCallback() {
        val gate = PendingAiAnswerGate()
        val stale = gate.arm("call-one")
        val current = gate.arm("call-one")

        assertNotEquals(stale, current)
        assertFalse(gate.consume("call-one", stale))
        assertTrue(gate.consume("call-one", current))
    }

    @Test
    fun callsAreCancelledIndependently() {
        val gate = PendingAiAnswerGate()
        val first = gate.arm("call-one")
        val second = gate.arm("call-two")

        gate.cancel("call-one")

        assertFalse(gate.consume("call-one", first))
        assertTrue(gate.consume("call-two", second))
    }

    @Test
    fun clearInvalidatesEveryCallback() {
        val gate = PendingAiAnswerGate()
        val first = gate.arm("call-one")
        val second = gate.arm("call-two")

        gate.clear()

        assertFalse(gate.consume("call-one", first))
        assertFalse(gate.consume("call-two", second))
    }
}
