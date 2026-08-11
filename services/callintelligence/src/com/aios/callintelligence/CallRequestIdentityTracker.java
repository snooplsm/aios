package com.aios.callintelligence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Android-free registry that prevents a reused call ID from accepting stale work. */
final class CallRequestIdentityTracker {
    private final Map<String, Object> active = new HashMap<>();

    synchronized boolean tryStart(String callId, Object identity, int maximumCalls) {
        Objects.requireNonNull(callId, "call ID");
        Objects.requireNonNull(identity, "request identity");
        if (maximumCalls <= 0) {
            throw new IllegalArgumentException("maximum calls must be positive");
        }
        if (!active.containsKey(callId) && active.size() >= maximumCalls) return false;
        active.put(callId, identity);
        return true;
    }

    synchronized boolean isCurrent(String callId, Object identity) {
        return identity != null && active.get(callId) == identity;
    }

    synchronized Object current(String callId) {
        return active.get(callId);
    }

    synchronized boolean finish(String callId, Object identity) {
        if (!isCurrent(callId, identity)) return false;
        active.remove(callId);
        return true;
    }

    synchronized Object remove(String callId) {
        return active.remove(callId);
    }

    synchronized boolean contains(String callId) {
        return active.containsKey(callId);
    }

    synchronized int size() {
        return active.size();
    }

    synchronized List<String> callIds() {
        return new ArrayList<>(active.keySet());
    }

    synchronized void clear() {
        active.clear();
    }
}
