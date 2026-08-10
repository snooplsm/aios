package com.aios.messaging.mms

enum class MmsOperationKind { SEND, DOWNLOAD, NOTIFY_RESPONSE }

enum class MmsOperationState { PREPARING, PROVIDER_PERSISTED, SUBMITTED, SUCCEEDED, FAILED }

/** Pure lifecycle rules shared by the durable store and host tests. */
object MmsOperationPolicy {
    const val RESULT_OK = -1
    const val MAX_PENDING_AGE_MILLIS = 24L * 60L * 60L * 1_000L

    fun canTransition(from: MmsOperationState, to: MmsOperationState): Boolean = when (from) {
        MmsOperationState.PREPARING ->
            to == MmsOperationState.PROVIDER_PERSISTED || to == MmsOperationState.FAILED
        MmsOperationState.PROVIDER_PERSISTED ->
            to == MmsOperationState.SUBMITTED || to == MmsOperationState.FAILED
        MmsOperationState.SUBMITTED ->
            to == MmsOperationState.SUCCEEDED || to == MmsOperationState.FAILED
        MmsOperationState.SUCCEEDED, MmsOperationState.FAILED -> false
    }

    fun recover(state: MmsOperationState, ageMillis: Long): MmsOperationState = when {
        state == MmsOperationState.PREPARING -> MmsOperationState.FAILED
        state == MmsOperationState.PROVIDER_PERSISTED -> MmsOperationState.FAILED
        state == MmsOperationState.SUBMITTED && ageMillis > MAX_PENDING_AGE_MILLIS ->
            MmsOperationState.FAILED
        else -> state
    }

    /** A submitted request may have crossed the process boundary even if its API call threw. */
    fun keepProviderRowAfterFailure(state: MmsOperationState): Boolean =
        state == MmsOperationState.SUBMITTED ||
            state == MmsOperationState.SUCCEEDED ||
            state == MmsOperationState.FAILED
}
