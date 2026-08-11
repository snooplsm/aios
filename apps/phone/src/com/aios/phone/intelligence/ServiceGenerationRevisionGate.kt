package com.aios.phone.intelligence

/** Maps per-process wire revisions onto one monotonic owner-visible sequence. */
internal class ServiceGenerationRevisionGate {
    private var wireRevision = 0L
    private var visibleRevision = 0L

    fun accept(candidateWireRevision: Long): Long? {
        if (candidateWireRevision <= wireRevision || candidateWireRevision <= 0L ||
            visibleRevision == Long.MAX_VALUE
        ) return null
        wireRevision = candidateWireRevision
        return ++visibleRevision
    }

    fun nextGeneration() {
        wireRevision = 0L
    }
}
