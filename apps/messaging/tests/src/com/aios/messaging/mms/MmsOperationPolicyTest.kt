package com.aios.messaging.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsOperationPolicyTest {
    @Test
    fun lifecycleOnlyMovesForward() {
        assertTrue(MmsOperationPolicy.canTransition(
            MmsOperationState.PREPARING,
            MmsOperationState.PROVIDER_PERSISTED,
        ))
        assertTrue(MmsOperationPolicy.canTransition(
            MmsOperationState.PROVIDER_PERSISTED,
            MmsOperationState.SUBMITTED,
        ))
        assertTrue(MmsOperationPolicy.canTransition(
            MmsOperationState.SUBMITTED,
            MmsOperationState.SUCCEEDED,
        ))
        assertFalse(MmsOperationPolicy.canTransition(
            MmsOperationState.SUCCEEDED,
            MmsOperationState.FAILED,
        ))
        assertFalse(MmsOperationPolicy.canTransition(
            MmsOperationState.SUBMITTED,
            MmsOperationState.PROVIDER_PERSISTED,
        ))
    }

    @Test
    fun recoveryFailsUnsafeOrExpiredWorkWithoutResending() {
        assertEquals(
            MmsOperationState.FAILED,
            MmsOperationPolicy.recover(MmsOperationState.PREPARING, 1L),
        )
        assertEquals(
            MmsOperationState.FAILED,
            MmsOperationPolicy.recover(MmsOperationState.PROVIDER_PERSISTED, 1L),
        )
        assertEquals(
            MmsOperationState.SUBMITTED,
            MmsOperationPolicy.recover(
                MmsOperationState.SUBMITTED,
                MmsOperationPolicy.MAX_PENDING_AGE_MILLIS,
            ),
        )
        assertEquals(
            MmsOperationState.FAILED,
            MmsOperationPolicy.recover(
                MmsOperationState.SUBMITTED,
                MmsOperationPolicy.MAX_PENDING_AGE_MILLIS + 1L,
            ),
        )
    }

    @Test
    fun carrierSubmittedRowsArePreservedOnSynchronousFailure() {
        assertFalse(MmsOperationPolicy.keepProviderRowAfterFailure(
            MmsOperationState.PREPARING,
        ))
        assertFalse(MmsOperationPolicy.keepProviderRowAfterFailure(
            MmsOperationState.PROVIDER_PERSISTED,
        ))
        assertTrue(MmsOperationPolicy.keepProviderRowAfterFailure(
            MmsOperationState.SUBMITTED,
        ))
        assertTrue(MmsOperationPolicy.keepProviderRowAfterFailure(
            MmsOperationState.SUCCEEDED,
        ))
        assertTrue(MmsOperationPolicy.keepProviderRowAfterFailure(
            MmsOperationState.FAILED,
        ))
    }
}
