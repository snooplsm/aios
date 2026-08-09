package com.aios.phone.telecom

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns Telecom RTT streams outside UI state. One worker serializes all reads and
 * writes, which preserves RttCall's non-thread-safe contract without blocking main.
 */
class RttSessionController(
    private val onRemoteText: (callId: String, chunk: String) -> Unit,
    private val onError: (callId: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aios-phone-rtt")
    }
    private val sessions = ConcurrentHashMap<String, Call.RttCall>()
    private var poller: ScheduledFuture<*>? = null

    @Synchronized
    fun attach(callId: String, rttCall: Call.RttCall) {
        sessions[callId] = rttCall
        if (poller?.isDone != false) {
            poller = worker.scheduleWithFixedDelay(
                ::poll,
                0L,
                POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    @Synchronized
    fun detach(callId: String) {
        sessions.remove(callId)
        if (sessions.isEmpty()) {
            poller?.cancel(false)
            poller = null
        }
    }

    fun write(callId: String, text: String) {
        if (text.isEmpty() || text.length > MAX_WRITE_CHARS) return
        worker.execute {
            val session = sessions[callId] ?: return@execute
            try {
                session.write(text)
            } catch (_: IOException) {
                detach(callId)
                main.post { onError(callId) }
            }
        }
    }

    private fun poll() {
        sessions.forEach { (callId, session) ->
            try {
                var chunk = session.readImmediately()
                while (chunk != null) {
                    val bounded = chunk.take(MAX_READ_CHARS)
                    if (bounded.isNotEmpty()) main.post { onRemoteText(callId, bounded) }
                    chunk = session.readImmediately()
                }
            } catch (_: IOException) {
                detach(callId)
                main.post { onError(callId) }
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val MAX_READ_CHARS = 1_000
        const val MAX_WRITE_CHARS = 1_000
    }
}
