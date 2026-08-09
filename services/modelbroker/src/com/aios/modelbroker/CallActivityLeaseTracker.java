package com.aios.modelbroker;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * UID-owned lifecycle tokens that make call priority process-lifetime bound.
 *
 * <p>The Android service supplies Binder tokens and owns their death recipients;
 * this state machine stays Android-free so ownership and transition behavior can
 * be verified by host tests.</p>
 */
final class CallActivityLeaseTracker<T> {
    private final Map<T, Integer> owners = new HashMap<>();

    /** Returns true only when this acquisition changes inactive to active. */
    synchronized boolean acquire(T token, int ownerUid) {
        Objects.requireNonNull(token, "lifecycle token");
        Integer existing = owners.get(token);
        if (existing != null) {
            requireOwner(existing, ownerUid);
            return false;
        }
        boolean becameActive = owners.isEmpty();
        owners.put(token, ownerUid);
        return becameActive;
    }

    /** Returns true only when this release removes the final active lease. */
    synchronized boolean release(T token, int ownerUid) {
        Objects.requireNonNull(token, "lifecycle token");
        Integer existing = owners.get(token);
        if (existing == null) {
            return false;
        }
        requireOwner(existing, ownerUid);
        owners.remove(token);
        return owners.isEmpty();
    }

    /** Binder-death path; returns true only when the final lease dies. */
    synchronized boolean removeDead(T token) {
        Objects.requireNonNull(token, "lifecycle token");
        if (owners.remove(token) == null) {
            return false;
        }
        return owners.isEmpty();
    }

    synchronized Integer ownerUid(T token) {
        return owners.get(token);
    }

    synchronized boolean isActive() {
        return !owners.isEmpty();
    }

    synchronized int size() {
        return owners.size();
    }

    /** Returns whether any lease was active before clearing all state. */
    synchronized boolean clear() {
        boolean wasActive = !owners.isEmpty();
        owners.clear();
        return wasActive;
    }

    private static void requireOwner(int existingOwnerUid, int callerUid) {
        if (existingOwnerUid != callerUid) {
            throw new SecurityException("call-activity lease is owned by another UID");
        }
    }
}
