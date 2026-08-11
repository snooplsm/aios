package com.aios.phone.model

/** Reduces two independently streaming ASR directions into one bounded UI timeline. */
object TranscriptTimelineReducer {
    private val directions = setOf("downlink", "uplink")
    private val languages = setOf("en", "es")

    fun reduce(
        current: List<TranscriptUiState>,
        candidate: TranscriptUiState,
        maximumSegments: Int,
    ): List<TranscriptUiState> {
        require(maximumSegments > 0) { "maximumSegments must be positive" }
        if (!isValid(candidate)) return current.takeLast(maximumSegments)

        val updated = current.toMutableList()
        val openTurn = updated.indexOfLast {
            !it.isFinal && it.direction == candidate.direction
        }
        if (openTurn >= 0) {
            // A direction has at most one provisional turn. Its cumulative partial
            // or final revision replaces that row even when the other direction
            // emitted between callbacks.
            updated[openTurn] = candidate
        } else {
            updated.add(candidate)
        }
        return updated.takeLast(maximumSegments)
    }

    private fun isValid(value: TranscriptUiState): Boolean =
        value.direction in directions &&
            value.language in languages &&
            value.text.isNotBlank() &&
            value.startMillis >= 0L
}
