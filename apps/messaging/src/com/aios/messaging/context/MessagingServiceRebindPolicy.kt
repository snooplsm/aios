package com.aios.messaging.context

/** Bounded retry state shared by long-lived AIOS Messaging service bindings. */
internal class MessagingServiceRebindPolicy {
    private var nextDelayMillis = INITIAL_DELAY_MILLIS
    private var scheduled = false
    private var closed = false

    @Synchronized
    fun reserve(immediate: Boolean): Long {
        if (closed || scheduled) return NO_RETRY
        scheduled = true
        if (immediate) return 0L
        val delay = nextDelayMillis
        nextDelayMillis = (nextDelayMillis * 2L).coerceAtMost(MAX_DELAY_MILLIS)
        return delay
    }

    @Synchronized
    fun begin(): Boolean {
        if (closed || !scheduled) return false
        scheduled = false
        return true
    }

    @Synchronized
    fun connected() {
        scheduled = false
        nextDelayMillis = INITIAL_DELAY_MILLIS
    }

    @Synchronized
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
