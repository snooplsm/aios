package com.aios.mediaintelligence;

/** Returns a stable retry reason while background media work must stop. */
@FunctionalInterface
interface MediaConstraintProbe {
    String blockedReason();
}
