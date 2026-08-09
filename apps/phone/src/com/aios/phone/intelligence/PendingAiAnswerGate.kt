package com.aios.phone.intelligence

/**
 * Main-thread state that makes a delayed AI-answer callback single-use and revocable.
 *
 * Removing a Handler callback is best effort once it is ready to run. The reservation
 * check is the authoritative guard: owner intent cancels the reservation before asking
 * Telecom to answer or reject, so a stale callback cannot later claim the call for AI.
 */
internal class PendingAiAnswerGate {
    private val reservations = mutableMapOf<String, Long>()
    private var nextReservation = 1L

    fun arm(callId: String): Long {
        require(callId.isNotBlank()) { "call ID must not be blank" }
        val reservation = nextReservation
        nextReservation = if (nextReservation == Long.MAX_VALUE) 1L else nextReservation + 1L
        reservations[callId] = reservation
        return reservation
    }

    fun consume(callId: String, reservation: Long): Boolean {
        if (reservations[callId] != reservation) return false
        reservations.remove(callId)
        return true
    }

    fun cancel(callId: String): Boolean = reservations.remove(callId) != null

    fun clear() = reservations.clear()
}
