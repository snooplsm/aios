package com.aios.callintelligence;

import java.util.function.LongSupplier;

/** One-way, monotonic transition from AI receptionist handling to owner handling. */
final class AssistantHandlingTracker {
    static final class Update {
        final boolean aiHandling;
        final long revision;
        final long observedAtEpochMillis;

        Update(boolean aiHandling, long revision, long observedAtEpochMillis) {
            this.aiHandling = aiHandling;
            this.revision = revision;
            this.observedAtEpochMillis = observedAtEpochMillis;
        }
    }

    private final LongSupplier clock;
    private boolean aiHandling;
    private Update published;
    private long revision;

    AssistantHandlingTracker(boolean initiallyHandledByAi) {
        this(initiallyHandledByAi, System::currentTimeMillis);
    }

    AssistantHandlingTracker(boolean initiallyHandledByAi, LongSupplier clock) {
        aiHandling = initiallyHandledByAi;
        this.clock = clock;
    }

    synchronized Update initial() {
        if (published == null) published = next(aiHandling);
        return published;
    }

    synchronized Update current() {
        return published;
    }

    synchronized boolean isAiHandling() {
        return aiHandling;
    }

    synchronized Update takeOver() {
        if (!aiHandling) return null;
        aiHandling = false;
        published = next(false);
        return published;
    }

    private Update next(boolean value) {
        return new Update(value, ++revision, clock.getAsLong());
    }
}
