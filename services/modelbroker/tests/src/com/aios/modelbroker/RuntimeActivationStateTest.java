package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.Test;

public final class RuntimeActivationStateTest {
    private static final VerifiedArtifact PRIMARY = artifact("primary");
    private static final VerifiedArtifact FALLBACK = artifact("fallback");

    @Test
    public void rejectionAdvancesThroughTheExactOrderedChain() {
        RuntimeActivationState state =
                new RuntimeActivationState(List.of(PRIMARY, FALLBACK));

        RuntimeActivationState.Attempt primary = state.beginNext();
        assertEquals(PRIMARY, primary.artifact);
        assertTrue(state.reject(primary));

        RuntimeActivationState.Attempt fallback = state.beginNext();
        assertEquals(FALLBACK, fallback.artifact);
        assertTrue(state.accept(fallback));
        assertEquals(FALLBACK, state.acceptedArtifact());
        assertNull(state.beginNext());
    }

    @Test
    public void earlyCallbackRejectsAttemptAndStaleCallbackCannotHitFallback() {
        RuntimeActivationState state =
                new RuntimeActivationState(List.of(PRIMARY, FALLBACK));

        RuntimeActivationState.Attempt primary = state.beginNext();
        assertFalse(state.allowCallback(primary));

        RuntimeActivationState.Attempt fallback = state.beginNext();
        assertTrue(state.accept(fallback));
        assertFalse(state.allowCallback(primary));
        assertTrue(state.allowCallback(fallback));
        assertEquals(FALLBACK, state.acceptedArtifact());
    }

    @Test
    public void staleRejectionCannotInvalidateCurrentAttempt() {
        RuntimeActivationState state =
                new RuntimeActivationState(List.of(PRIMARY, FALLBACK));
        RuntimeActivationState.Attempt primary = state.beginNext();
        assertTrue(state.reject(primary));
        RuntimeActivationState.Attempt fallback = state.beginNext();

        assertFalse(state.reject(primary));
        assertTrue(state.accept(fallback));
        assertTrue(state.allowCallback(fallback));
    }

    @Test
    public void unresolvedAttemptCannotBeSkipped() {
        RuntimeActivationState state =
                new RuntimeActivationState(List.of(PRIMARY, FALLBACK));
        state.beginNext();

        assertThrows(IllegalStateException.class, state::beginNext);
    }

    @Test
    public void exactChainFailsClosedAfterItsOnlyAttempt() {
        RuntimeActivationState state = new RuntimeActivationState(List.of(PRIMARY));
        assertTrue(state.reject(state.beginNext()));
        assertNull(state.beginNext());
        assertNull(state.acceptedArtifact());
    }

    private static VerifiedArtifact artifact(String id) {
        return new VerifiedArtifact(
                id,
                new File(id + ".bin"),
                "0".repeat(64),
                1L,
                "test-runtime",
                "cpu",
                List.of("text_generation"),
                List.of("en"));
    }
}
