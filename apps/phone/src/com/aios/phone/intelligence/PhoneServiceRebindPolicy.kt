package com.aios.phone.intelligence

/** Bounded retry state shared by long-lived AIOS Phone service bindings. */
internal class PhoneServiceRebindPolicy {
    private var nextDelayMillis = INITIAL_DELAY_MILLIS
    private var scheduled = false
    private var closed = false

    fun reserve(immediate: Boolean): Long {
        if (closed || scheduled) return NO_RETRY
        scheduled = true
        if (immediate) return 0L
        val delay = nextDelayMillis
        nextDelayMillis = (nextDelayMillis * 2L).coerceAtMost(MAX_DELAY_MILLIS)
        return delay
    }

    fun begin(): Boolean {
        if (closed || !scheduled) return false
        scheduled = false
        return true
    }

    fun connected() {
        scheduled = false
        nextDelayMillis = INITIAL_DELAY_MILLIS
    }

    fun close() {
        closed = true
        scheduled = false
    }

    companion object {
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 60_000L
        const val NO_RETRY = -1L
    }
}
