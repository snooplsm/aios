package com.aios.mediaintelligence;

/** Pure decision policy for resetting a persisted MediaStore generation cursor. */
final class MediaGenerationBaselinePolicy {
    private MediaGenerationBaselinePolicy() {}

    static boolean requiresBaseline(
            String currentVersion,
            long currentGeneration,
            boolean hasStoredState,
            String storedVersion,
            long storedGeneration) {
        return !hasStoredState
                || !currentVersion.equals(storedVersion)
                || currentGeneration < storedGeneration;
    }
}
