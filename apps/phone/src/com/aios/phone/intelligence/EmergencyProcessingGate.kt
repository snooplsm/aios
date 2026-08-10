package com.aios.phone.intelligence

/**
 * Latches every authoritative emergency signal for one Telecom call and
 * invalidates asynchronous number checks when a stronger signal arrives.
 */
internal class EmergencyProcessingGate(
    networkIdentified: Boolean,
    emergencyCallbackMode: Boolean,
) {
    private var revision = 0L
    private var networkEmergency = networkIdentified
    private var callbackMode = emergencyCallbackMode
    private var numberEmergency = false

    @Synchronized
    fun isProtected(): Boolean = networkEmergency || callbackMode || numberEmergency

    @Synchronized
    fun isEmergencyCall(): Boolean = networkEmergency || numberEmergency

    @Synchronized
    fun isEmergencyCallbackMode(): Boolean = callbackMode

    @Synchronized
    fun beginNumberCheck(): Long = ++revision

    /** Returns false when a later Telecom signal made this result stale. */
    @Synchronized
    fun completeNumberCheck(expectedRevision: Long, emergency: Boolean): Boolean {
        if (expectedRevision != revision) return false
        numberEmergency = numberEmergency || emergency
        return true
    }

    /** Returns true only on the transition into emergency protection. */
    @Synchronized
    fun observeTelecom(networkIdentified: Boolean, emergencyCallbackMode: Boolean): Boolean {
        val wasProtected = networkEmergency || callbackMode || numberEmergency
        val changed = (networkIdentified && !networkEmergency) ||
            (emergencyCallbackMode && !callbackMode)
        networkEmergency = networkEmergency || networkIdentified
        callbackMode = callbackMode || emergencyCallbackMode
        if (changed) revision++
        return !wasProtected && (networkEmergency || callbackMode || numberEmergency)
    }
}
