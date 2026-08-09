package com.aios.phone.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/** Owns only the earpiece-call proximity screen-off wake lock. */
class ProximityLockController(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val lock: PowerManager.WakeLock? = power?.takeIf {
        it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
    }?.newWakeLock(
        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
        "AIOS Phone:proximity",
    )?.apply { setReferenceCounted(false) }

    // Telecom state, endpoint changes, and service teardown all call update(false).
    // A timeout would incorrectly relight the screen during a long earpiece call.
    @SuppressLint("Wakelock", "WakelockTimeout")
    fun update(activeCallOnEarpiece: Boolean) {
        val value = lock ?: return
        if (activeCallOnEarpiece && !value.isHeld) {
            value.acquire()
        } else if (!activeCallOnEarpiece && value.isHeld) {
            value.release()
        }
    }
}
