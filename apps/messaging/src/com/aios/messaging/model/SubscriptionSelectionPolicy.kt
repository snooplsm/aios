package com.aios.messaging.model

/** Pure outgoing-route policy; an ambiguous multi-SIM device must ask the owner. */
object SubscriptionSelectionPolicy {
    fun select(
        activeSubscriptionIds: Collection<Int>,
        preferredSubscriptionId: Int?,
        defaultSubscriptionId: Int?,
    ): Int? {
        val active = activeSubscriptionIds.filter { it >= 0 }.distinct()
        return when {
            preferredSubscriptionId != null && preferredSubscriptionId in active ->
                preferredSubscriptionId
            defaultSubscriptionId != null && defaultSubscriptionId in active ->
                defaultSubscriptionId
            active.size == 1 -> active.single()
            else -> null
        }
    }
}
