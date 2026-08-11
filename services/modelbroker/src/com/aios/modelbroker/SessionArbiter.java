package com.aios.modelbroker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Thread-safe capacity/quota arbiter independent of any inference runtime.
 * Runtime callbacks are performed by the owner after the lock is released.
 */
final class SessionArbiter {
    enum Status {
        ACTIVE,
        QUEUED,
        REJECTED_QUOTA
    }

    static final class Change {
        final Status submittedStatus;
        final List<Long> cancelled;
        final List<Long> activated;

        Change(Status submittedStatus, List<Long> cancelled, List<Long> activated) {
            this.submittedStatus = submittedStatus;
            this.cancelled = List.copyOf(cancelled);
            this.activated = List.copyOf(activated);
        }
    }

    private static final class Lease {
        final long sessionId;
        final int ownerUid;
        final WorkClass workClass;
        final long sequence;
        boolean active;

        Lease(long sessionId, int ownerUid, WorkClass workClass, long sequence) {
            this.sessionId = sessionId;
            this.ownerUid = ownerUid;
            this.workClass = workClass;
            this.sequence = sequence;
        }
    }

    private final SessionCapacityPolicy capacityPolicy;
    private final Map<Long, Lease> leases = new HashMap<>();
    private final PriorityQueue<Lease> queue = new PriorityQueue<>(
            Comparator.<Lease>comparingInt(lease -> lease.workClass.priority)
                    .reversed()
                    .thenComparingLong(lease -> lease.sequence));
    private long sequence;
    private boolean callActive;

    SessionArbiter(SessionCapacityPolicy capacityPolicy) {
        if (capacityPolicy == null) {
            throw new IllegalArgumentException("capacity policy is required");
        }
        this.capacityPolicy = capacityPolicy;
    }

    synchronized Change submit(
            long sessionId, int ownerUid, WorkClass workClass, int maxOwnerSessions) {
        if (leases.containsKey(sessionId)) {
            throw new IllegalArgumentException("duplicate session ID");
        }
        if (countOwner(ownerUid) >= maxOwnerSessions) {
            return new Change(Status.REJECTED_QUOTA, List.of(), List.of());
        }

        List<Long> cancelled = new ArrayList<>();
        if (workClass != WorkClass.MEDIA_BACKGROUND) {
            cancelled.addAll(removeWorkClass(WorkClass.MEDIA_BACKGROUND));
        }

        Lease lease = new Lease(sessionId, ownerUid, workClass, sequence++);
        leases.put(sessionId, lease);
        if (workClass == WorkClass.MEDIA_BACKGROUND && mediaBlocked()) {
            queue.add(lease);
            return new Change(Status.QUEUED, cancelled, List.of());
        }
        if (canActivate(workClass)) {
            lease.active = true;
            return new Change(Status.ACTIVE, cancelled, List.of(sessionId));
        }

        Lease victim = lowestPriorityActiveForAdmission(workClass);
        if (victim != null
                && victim.workClass.priority < workClass.priority) {
            leases.remove(victim.sessionId);
            victim.active = false;
            cancelled.add(victim.sessionId);
            lease.active = true;
            return new Change(Status.ACTIVE, cancelled, List.of(sessionId));
        }
        queue.add(lease);
        return new Change(Status.QUEUED, cancelled, List.of());
    }

    synchronized Change finish(long sessionId, int ownerUid) {
        Lease lease = requireOwner(sessionId, ownerUid);
        boolean wasActive = lease.active;
        leases.remove(sessionId);
        queue.remove(lease);
        List<Long> activated = wasActive ? promote() : List.of();
        return new Change(null, List.of(), activated);
    }

    synchronized Change setCallActive(boolean active) {
        callActive = active;
        List<Long> cancelled = active
                ? removeWorkClass(WorkClass.MEDIA_BACKGROUND)
                : List.of();
        List<Long> activated = active ? List.of() : promote();
        return new Change(null, cancelled, activated);
    }

    synchronized Change preemptBackgroundForMemoryPressure() {
        return new Change(null, removeWorkClass(WorkClass.MEDIA_BACKGROUND), List.of());
    }

    synchronized boolean isOwner(long sessionId, int uid) {
        Lease lease = leases.get(sessionId);
        return lease != null && lease.ownerUid == uid;
    }

    synchronized int size() {
        return leases.size();
    }

    private int countOwner(int uid) {
        int count = 0;
        for (Lease lease : leases.values()) {
            if (lease.ownerUid == uid) {
                count++;
            }
        }
        return count;
    }

    private int activeCount() {
        int count = 0;
        for (Lease lease : leases.values()) {
            if (lease.active) {
                count++;
            }
        }
        return count;
    }

    private int activeCount(WorkClass workClass) {
        int count = 0;
        for (Lease lease : leases.values()) {
            if (lease.active
                    && capacityPolicy.sharesActivePool(workClass, lease.workClass)) {
                count++;
            }
        }
        return count;
    }

    private boolean hasClassHeadroom(WorkClass workClass) {
        return activeCount(workClass) < capacityPolicy.activeLimit(workClass);
    }

    private boolean canActivate(WorkClass workClass) {
        return activeCount() < capacityPolicy.globalSessionCapacity
                && hasClassHeadroom(workClass);
    }

    private Lease lowestPriorityActiveForAdmission(WorkClass incoming) {
        boolean classFull = !hasClassHeadroom(incoming);
        boolean globalFull = activeCount() >= capacityPolicy.globalSessionCapacity;
        if (!classFull && !globalFull) {
            return null;
        }
        Lease result = null;
        for (Lease lease : leases.values()) {
            if (lease.active
                    && (!classFull
                    || capacityPolicy.sharesActivePool(incoming, lease.workClass))
                    && (result == null
                    || lease.workClass.priority < result.workClass.priority
                    || (lease.workClass.priority == result.workClass.priority
                    && lease.sequence > result.sequence))) {
                result = lease;
            }
        }
        return result;
    }

    private List<Long> removeWorkClass(WorkClass target) {
        List<Long> removed = new ArrayList<>();
        List<Lease> snapshot = new ArrayList<>(leases.values());
        for (Lease lease : snapshot) {
            if (lease.workClass == target) {
                leases.remove(lease.sessionId);
                queue.remove(lease);
                removed.add(lease.sessionId);
            }
        }
        return removed;
    }

    private List<Long> promote() {
        List<Long> result = new ArrayList<>();
        List<Lease> deferred = new ArrayList<>();
        while (activeCount() < capacityPolicy.globalSessionCapacity && !queue.isEmpty()) {
            Lease next = queue.poll();
            if (!leases.containsKey(next.sessionId)) {
                continue;
            }
            if ((mediaBlocked() && next.workClass == WorkClass.MEDIA_BACKGROUND)
                    || !hasClassHeadroom(next.workClass)) {
                deferred.add(next);
                continue;
            }
            next.active = true;
            result.add(next.sessionId);
        }
        queue.addAll(deferred);
        return result;
    }

    private boolean mediaBlocked() {
        if (callActive) {
            return true;
        }
        for (Lease lease : leases.values()) {
            if (lease.workClass != WorkClass.MEDIA_BACKGROUND) {
                return true;
            }
        }
        return false;
    }

    private Lease requireOwner(long sessionId, int ownerUid) {
        Lease lease = leases.get(sessionId);
        if (lease == null || lease.ownerUid != ownerUid) {
            throw new SecurityException("session is absent or owned by another UID");
        }
        return lease;
    }
}
