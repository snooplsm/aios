package com.aios.callintelligence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Android-free state for UID-owned Telecom lifecycle tokens and opaque call IDs. */
final class TelecomCallPresenceTracker<T> {
    static final class Release {
        final boolean callRemoved;
        final boolean callOrphaned;
        final boolean activityChanged;

        Release(boolean callRemoved, boolean callOrphaned, boolean activityChanged) {
            this.callRemoved = callRemoved;
            this.callOrphaned = callOrphaned;
            this.activityChanged = activityChanged;
        }
    }

    static final class DeadClient {
        final Integer ownerUid;
        final Set<String> orphanedCallIds;
        final boolean activityChanged;

        DeadClient(Integer ownerUid, Set<String> orphanedCallIds, boolean activityChanged) {
            this.ownerUid = ownerUid;
            this.orphanedCallIds = Set.copyOf(orphanedCallIds);
            this.activityChanged = activityChanged;
        }
    }

    private static final class ClientCalls {
        final int ownerUid;
        final Set<String> callIds = new HashSet<>();

        ClientCalls(int ownerUid) {
            this.ownerUid = ownerUid;
        }
    }

    private final int maxTokens;
    private final int maxCallsPerToken;
    private final Map<T, ClientCalls> clients = new HashMap<>();
    private int totalCalls;

    TelecomCallPresenceTracker(int maxTokens, int maxCallsPerToken) {
        if (maxTokens <= 0 || maxCallsPerToken <= 0) {
            throw new IllegalArgumentException("maximum token and call counts must be positive");
        }
        this.maxTokens = maxTokens;
        this.maxCallsPerToken = maxCallsPerToken;
    }

    /** Returns true only when this operation changes overall call presence. */
    synchronized boolean setPresent(T token, int ownerUid, String callId, boolean present) {
        Objects.requireNonNull(token, "lifecycle token");
        Objects.requireNonNull(callId, "call ID");
        if (!present) {
            return releaseAndReport(token, ownerUid, callId).activityChanged;
        }
        boolean wasActive = totalCalls > 0;
        ClientCalls calls = clients.get(token);
        if (calls != null && calls.ownerUid != ownerUid) {
            throw new SecurityException("Telecom lifecycle token is owned by another UID");
        }
        Integer existingCallOwner = ownerUidForCall(callId);
        if (existingCallOwner != null && existingCallOwner != ownerUid) {
            throw new SecurityException("Telecom call ID is owned by another UID");
        }
        if (calls == null) {
            if (clients.size() >= maxTokens) {
                throw new IllegalStateException("too many Telecom lifecycle tokens");
            }
            calls = new ClientCalls(ownerUid);
            clients.put(token, calls);
        }
        if (!calls.callIds.contains(callId)
                && calls.callIds.size() >= maxCallsPerToken) {
            throw new IllegalStateException("too many calls for Telecom lifecycle token");
        }
        if (calls.callIds.add(callId)) {
            totalCalls++;
        }
        return wasActive != (totalCalls > 0);
    }

    /** Atomically releases one assertion and reports whether its call became orphaned. */
    synchronized Release releaseAndReport(T token, int ownerUid, String callId) {
        Objects.requireNonNull(token, "lifecycle token");
        Objects.requireNonNull(callId, "call ID");
        boolean wasActive = totalCalls > 0;
        ClientCalls calls = clients.get(token);
        if (calls != null && calls.ownerUid != ownerUid) {
            throw new SecurityException("Telecom lifecycle token is owned by another UID");
        }
        boolean removed = calls != null && calls.callIds.remove(callId);
        if (removed) {
            totalCalls--;
            if (calls.callIds.isEmpty()) {
                clients.remove(token);
            }
        }
        return new Release(
                removed,
                removed && !ownsCall(ownerUid, callId),
                wasActive != (totalCalls > 0));
    }

    /** Binder-death path; returns true only when overall presence changes. */
    synchronized boolean removeDead(T token) {
        return removeDeadAndReport(token).activityChanged;
    }

    /** Atomically removes a dead token and reports calls with no surviving owner token. */
    synchronized DeadClient removeDeadAndReport(T token) {
        Objects.requireNonNull(token, "lifecycle token");
        boolean wasActive = totalCalls > 0;
        ClientCalls removed = clients.remove(token);
        if (removed == null) {
            return new DeadClient(null, Set.of(), false);
        }
        totalCalls -= removed.callIds.size();
        Set<String> orphaned = new HashSet<>();
        for (String callId : removed.callIds) {
            if (!ownsCall(removed.ownerUid, callId)) {
                orphaned.add(callId);
            }
        }
        return new DeadClient(
                removed.ownerUid, orphaned, wasActive != (totalCalls > 0));
    }

    synchronized Integer ownerUid(T token) {
        ClientCalls calls = clients.get(token);
        return calls == null ? null : calls.ownerUid;
    }

    synchronized boolean ownsCall(int ownerUid, String callId) {
        Integer existingOwner = ownerUidForCall(callId);
        return existingOwner != null && existingOwner == ownerUid;
    }

    synchronized Set<String> callIds(T token) {
        ClientCalls calls = clients.get(token);
        return calls == null ? Set.of() : Set.copyOf(calls.callIds);
    }

    synchronized boolean isActive() {
        return totalCalls > 0;
    }

    synchronized int totalCalls() {
        return totalCalls;
    }

    synchronized int tokenCount() {
        return clients.size();
    }

    synchronized void clear() {
        clients.clear();
        totalCalls = 0;
    }

    private Integer ownerUidForCall(String callId) {
        for (ClientCalls calls : clients.values()) {
            if (calls.callIds.contains(callId)) {
                return calls.ownerUid;
            }
        }
        return null;
    }
}
