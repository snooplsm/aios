package com.aios.modelbroker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Tracks elapsed-realtime session deadlines without depending on wall-clock time. */
final class SessionDeadlineQueue {
    private static final class Entry implements Comparable<Entry> {
        final long sessionId;
        final long deadlineMillis;

        Entry(long sessionId, long deadlineMillis) {
            this.sessionId = sessionId;
            this.deadlineMillis = deadlineMillis;
        }

        @Override
        public int compareTo(Entry other) {
            int byDeadline = Long.compare(deadlineMillis, other.deadlineMillis);
            return byDeadline != 0 ? byDeadline : Long.compare(sessionId, other.sessionId);
        }
    }

    private final Map<Long, Long> deadlines = new HashMap<>();
    private final PriorityQueue<Entry> ordered = new PriorityQueue<>();

    void add(long sessionId, long deadlineMillis) {
        if (sessionId <= 0L || deadlineMillis <= 0L) {
            throw new IllegalArgumentException("session ID and deadline must be positive");
        }
        if (deadlines.putIfAbsent(sessionId, deadlineMillis) != null) {
            throw new IllegalArgumentException("session deadline already exists");
        }
        ordered.add(new Entry(sessionId, deadlineMillis));
    }

    boolean remove(long sessionId) {
        return deadlines.remove(sessionId) != null;
    }

    List<Long> removeExpired(long nowMillis) {
        List<Long> expired = new ArrayList<>();
        while (true) {
            Entry next = currentHead();
            if (next == null || next.deadlineMillis > nowMillis) {
                return expired;
            }
            ordered.remove();
            deadlines.remove(next.sessionId);
            expired.add(next.sessionId);
        }
    }

    long millisUntilNext(long nowMillis) {
        Entry next = currentHead();
        if (next == null) {
            return Long.MAX_VALUE;
        }
        return next.deadlineMillis <= nowMillis ? 0L : next.deadlineMillis - nowMillis;
    }

    void clear() {
        deadlines.clear();
        ordered.clear();
    }

    int size() {
        return deadlines.size();
    }

    private Entry currentHead() {
        while (true) {
            Entry next = ordered.peek();
            if (next == null) {
                return null;
            }
            Long currentDeadline = deadlines.get(next.sessionId);
            if (currentDeadline != null && currentDeadline == next.deadlineMillis) {
                return next;
            }
            ordered.remove();
        }
    }
}
