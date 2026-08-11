package com.aios.modelbroker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Android-free policy for measured memory/thermal pressure at request time. */
final class RuntimePressurePolicy {
    static final int THERMAL_STATUS_UNKNOWN = -1;
    static final int THERMAL_STATUS_SEVERE = 3;

    enum Decision {
        PREFER_QUALITY,
        PREFER_LOWER_MEMORY,
        BLOCK_BACKGROUND
    }

    private RuntimePressurePolicy() {}

    static Decision decide(
            WorkClass workClass,
            boolean memoryStateKnown,
            boolean lowMemory,
            int thermalStatus) {
        if (workClass == null || thermalStatus < THERMAL_STATUS_UNKNOWN
                || thermalStatus > 6) {
            throw new IllegalArgumentException("invalid runtime-pressure input");
        }
        boolean constrained = !memoryStateKnown
                || lowMemory
                || thermalStatus == THERMAL_STATUS_UNKNOWN
                || thermalStatus >= THERMAL_STATUS_SEVERE;
        if (!constrained) return Decision.PREFER_QUALITY;
        return workClass == WorkClass.MEDIA_BACKGROUND
                ? Decision.BLOCK_BACKGROUND
                : Decision.PREFER_LOWER_MEMORY;
    }

    static List<VerifiedArtifact> order(
            List<VerifiedArtifact> candidates, Decision decision) {
        if (candidates == null || decision == null) {
            throw new IllegalArgumentException("pressure decision and candidates required");
        }
        if (decision != Decision.PREFER_LOWER_MEMORY || candidates.size() < 2) {
            return List.copyOf(candidates);
        }
        ArrayList<VerifiedArtifact> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong(item -> item.estimatedResidentMb));
        return List.copyOf(ordered);
    }
}
