package com.aios.modelbroker;

import java.util.List;

/** Ordered, fail-closed activation state for one admitted runtime candidate chain. */
final class RuntimeActivationState {
    static final class Attempt {
        final VerifiedArtifact artifact;

        private Attempt(VerifiedArtifact artifact) {
            this.artifact = artifact;
        }
    }

    private final List<VerifiedArtifact> candidates;
    private int nextIndex;
    private Attempt current;
    private Attempt accepted;

    RuntimeActivationState(List<VerifiedArtifact> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("runtime candidate chain is empty");
        }
        this.candidates = List.copyOf(candidates);
    }

    Attempt beginNext() {
        if (accepted != null) return null;
        if (current != null) {
            throw new IllegalStateException("current runtime attempt is unresolved");
        }
        if (nextIndex >= candidates.size()) return null;
        current = new Attempt(candidates.get(nextIndex++));
        return current;
    }

    boolean reject(Attempt attempt) {
        if (attempt == null || current != attempt || accepted != null) return false;
        current = null;
        return true;
    }

    boolean accept(Attempt attempt) {
        if (attempt == null || current != attempt || accepted != null) return false;
        accepted = attempt;
        current = null;
        return true;
    }

    /**
     * Allows callbacks only from the accepted attempt. A callback delivered
     * synchronously during open rejects that attempt so the caller can continue.
     */
    boolean allowCallback(Attempt attempt) {
        if (accepted == attempt) return true;
        reject(attempt);
        return false;
    }

    VerifiedArtifact acceptedArtifact() {
        return accepted == null ? null : accepted.artifact;
    }
}
